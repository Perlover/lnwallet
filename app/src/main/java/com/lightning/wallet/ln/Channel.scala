package com.lightning.wallet.ln

import com.softwaremill.quicklens._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.Channel._
import com.lightning.wallet.ln.Exceptions._

import com.lightning.wallet.ln.crypto.{Generators, ShaHashesWithIndex}
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import com.lightning.wallet.ln.Helpers.{Closing, Funding}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey}


class Channel extends StateMachine[ChannelData] { me =>
  def doProcess(change: Any): Unit = (data, change, state) match {
    case (InitData(announce), cmd @ CMDOpenChannel(localParams, temporaryChannelId,
    initialFeeratePerKw, pushMsat, _, fundingAmountSat), WAIT_FOR_INIT) =>

      val firstPerCommitPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
      val open = OpenChannel(localParams.chainHash, temporaryChannelId, fundingAmountSat, pushMsat,
        localParams.dustLimitSatoshis, localParams.maxHtlcValueInFlightMsat, localParams.channelReserveSat,
        localParams.htlcMinimumMsat, initialFeeratePerKw, localParams.toSelfDelay, localParams.maxAcceptedHtlcs,
        localParams.fundingPrivKey.publicKey, localParams.revocationSecret.toPoint, localParams.paymentKey.toPoint,
        localParams.delayedPaymentKey.toPoint, firstPerCommitPoint)

      become(WaitAcceptData(announce, cmd, open), WAIT_FOR_ACCEPT)


    // GUARD: remote requires us to keep too much in local reserve which is not acceptable
    case (wait @ WaitAcceptData(announce, cmd, _), accept: AcceptChannel, WAIT_FOR_ACCEPT) =>
      val exceedsReserve: Boolean = LNParams.exceedsReserve(accept.channelReserveSatoshis, cmd.fundingAmountSat)
      if (exceedsReserve) become(wait, FINISHED) else become(WaitFundingData(announce, cmd, accept), WAIT_FOR_FUNDING)


    // They have accepted our proposal, now let them sign a first commit tx
    case (WaitFundingData(announce, cmd, accept), (fundTx: Transaction, outIndex: Int), WAIT_FOR_FUNDING) =>
      val remoteParams = RemoteParams(accept.dustLimitSatoshis, accept.maxHtlcValueInFlightMsat, accept.channelReserveSatoshis,
        accept.htlcMinimumMsat, accept.toSelfDelay, accept.maxAcceptedHtlcs, accept.fundingPubkey, accept.revocationBasepoint,
        accept.paymentBasepoint, accept.delayedPaymentBasepoint, cmd.remoteInit.globalFeatures, cmd.remoteInit.localFeatures)

      val (localSpec, localCommitTx, remoteSpec, remoteCommitTx) =
        Funding.makeFirstFunderCommitTxs(cmd, remoteParams, fundTx.hash,
          outIndex, accept.firstPerCommitmentPoint)

      val localSigOfRemoteTx = Scripts.sign(remoteCommitTx, cmd.localParams.fundingPrivKey)
      val fundingCreated = FundingCreated(cmd.temporaryChannelId, fundTx.hash, outIndex, localSigOfRemoteTx)
      val firstRemoteCommit = RemoteCommit(0L, remoteSpec, remoteCommitTx.tx.txid, accept.firstPerCommitmentPoint)

      become(WaitFundingSignedData(announce, cmd.localParams, Tools.toLongId(fundTx.hash, outIndex),
        remoteParams, fundTx, localSpec, localCommitTx, firstRemoteCommit, fundingCreated),
        WAIT_FUNDING_SIGNED)


    // They have signed our first commit tx, we can broadcast a funding tx
    case (wait: WaitFundingSignedData, remote: FundingSigned, WAIT_FUNDING_SIGNED) =>
      val signedLocalCommitTx = Scripts.addSigs(wait.localCommitTx, wait.localParams.fundingPrivKey.publicKey,
        wait.remoteParams.fundingPubKey, Scripts.sign(wait.localCommitTx, wait.localParams.fundingPrivKey), remote.signature)

      if (Scripts.checkSpendable(signedLocalCommitTx).isFailure) become(wait, FINISHED) else {
        val localCommit = LocalCommit(0L, wait.localSpec, PublishableTxs(Nil, signedLocalCommitTx), null)
        val commitments = Commitments(wait.localParams, wait.remoteParams, localCommit, wait.remoteCommit,
          localChanges = Changes(proposed = Vector.empty, signed = Vector.empty, acked = Vector.empty),
          remoteChanges = Changes(proposed = Vector.empty, signed = Vector.empty, Vector.empty),
          localNextHtlcId = 0L, remoteNextHtlcId = 0L, remoteNextCommitInfo = Right(null),
          unackedMessages = Vector.empty, commitInput = wait.localCommitTx.input,
          ShaHashesWithIndex(Map.empty, None), wait.channelId)

        // At this point funding tx should be broadcasted
        become(WaitFundingConfirmedData(wait.announce, None,
          None, wait.fundingTx, commitments), WAIT_FUNDING_DONE)
      }


    // Channel closing in WAIT_FOR_INIT | WAIT_FOR_ACCEPT | WAIT_FOR_FUNDING | WAIT_FUNDING_SIGNED
    case (some, CMDShutdown, WAIT_FOR_INIT | WAIT_FOR_ACCEPT | WAIT_FOR_FUNDING | WAIT_FUNDING_SIGNED) => become(some, FINISHED)
    case (some, _: Error, WAIT_FOR_INIT | WAIT_FOR_ACCEPT | WAIT_FOR_FUNDING | WAIT_FUNDING_SIGNED) => become(some, FINISHED)


    // FUNDING TX IS BROADCASTED AT THIS POINT


    // We have not yet sent a FundingLocked to them but just got a FundingLocked from them so we keep it
    case (wait @ WaitFundingConfirmedData(_, None, _, _, _), their: FundingLocked, WAIT_FUNDING_DONE) =>
      me stayWith wait.copy(their = Some apply their)

    // We have got a FundingDepthOk blockchain event but have not yet got a confirmation from them
    case (wait @ WaitFundingConfirmedData(_, _, None, _, commitments), CMDDepth(LNParams.minDepth), WAIT_FUNDING_DONE) =>
      val ourLocked = Some(me makeFundingLocked commitments)
      me stayWith wait.copy(our = ourLocked)

    // We have already sent them a FundingLocked some time ago, now we got a FundingLocked from them
    case (wait @ WaitFundingConfirmedData(_, Some(our), _, _, _), their: FundingLocked, WAIT_FUNDING_DONE) =>
      becomeNormal(wait, our, their.nextPerCommitmentPoint)

    // They have already sent us a FundingLocked message so we got it saved and now we get a FundingDepthOk blockchain event
    case (wait @ WaitFundingConfirmedData(_, _, Some(their), _, commitments), CMDDepth(LNParams.minDepth), WAIT_FUNDING_DONE) =>
      becomeNormal(wait, me makeFundingLocked commitments, their.nextPerCommitmentPoint)


    // Channel closing in WAIT_FUNDING_DONE (from now on we have a funding transaction)
    case (wait: WaitFundingConfirmedData, _: Error, WAIT_FUNDING_DONE) => startLocalCurrentClose(wait)
    case (wait: WaitFundingConfirmedData, CMDShutdown, WAIT_FUNDING_DONE) => startLocalCurrentClose(wait)

    case (wait: WaitFundingConfirmedData, CMDFundingSpent(tx), WAIT_FUNDING_DONE) =>
      // We don't yet have any revoked commits so they either spent a first commit or it's an info leak
      if (tx.txid == wait.commitments.remoteCommit.txid) startRemoteCurrentClose(wait, tx)
      else startLocalCurrentClose(wait)


    // NORMAL MODE


    case (norm: NormalData, cmd: CMDAddHtlc, NORMAL)
      // Throw an exception so we can do something about it
      // GUARD: can't add new outgoing HTLCs when closing is in progress
      if norm.localShutdown.isDefined | norm.remoteShutdown.isDefined =>
      throw DetailedException(CHANNEL_SHUTDOWN_IN_PROGRESS, cmd)


    case (norm: NormalData, cmd: CMDAddHtlc, NORMAL) =>
      val chainHeight: Int = LNParams.broadcaster.currentHeight
      val c1 = Commitments.sendAdd(norm.commitments, cmd, chainHeight)
      me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    case (norm: NormalData, add: UpdateAddHtlc, NORMAL) =>
      val chainHeight: Int = LNParams.broadcaster.currentHeight
      val c1 = Commitments.receiveAdd(norm.commitments, add, chainHeight)
      me stayWith norm.copy(commitments = c1)


    // We're fulfilling an HTLC we got earlier
    case (norm: NormalData, cmd: CMDFulfillHtlc, NORMAL) =>
      val c1 = Commitments.sendFulfill(norm.commitments, cmd)
      me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    // Got a fulfill for an HTLC we sent earlier
    case (norm: NormalData, fulfill: UpdateFulfillHtlc, NORMAL) =>
      val c1 = Commitments.receiveFulfill(norm.commitments, fulfill)
      me stayWith norm.copy(commitments = c1)


    // Failing an HTLC we got earlier
    case (norm: NormalData, cmd: CMDFailHtlc, NORMAL) =>
      val c1 = Commitments.sendFail(norm.commitments, cmd)
      me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    case (norm: NormalData, cmd: CMDFailMalformedHtlc, NORMAL) =>
      val c1 = Commitments.sendFailMalformed(norm.commitments, cmd)
      me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    // Got a failure for an HTLC we sent earlier
    case (norm: NormalData, fail: FailHtlc, NORMAL) =>
      val c1 = Commitments.receiveFail(norm.commitments, fail)
      me stayWith norm.copy(commitments = c1)


    // Send new commit tx signature
    case (norm: NormalData, CMDCommitSig, NORMAL)
      // GUARD: we only send if in the correct state
      if me canSendCommitSig norm.commitments =>

      val nextRemotePoint = norm.commitments.remoteNextCommitInfo.right.get
      val c1 = Commitments.sendCommit(norm.commitments, nextRemotePoint)
      me stayWith norm.copy(commitments = c1)


    // GUARD: we have received a commit sig from them when shutdown is fully confirmed by both parties
    case (norm @ NormalData(announce, commitments, Some(local), Some(remote), _), sig: CommitSig, NORMAL) =>

      val c1 = Commitments.receiveCommit(commitments, sig)
      val canNegotiate = Commitments hasNoPendingHtlcs c1

      // Channel closing negotiations may start because if changes left
      if (canNegotiate) startNegotiations(announce, c1, local, remote)
      else me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    // We received a commit sig from them
    case (norm: NormalData, sig: CommitSig, NORMAL) =>
      val c1 = Commitments.receiveCommit(norm.commitments, sig)
      me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    // We received a revocation because we sent a sig
    case (norm: NormalData, rev: RevokeAndAck, NORMAL) =>
      val c1 = Commitments.receiveRevocation(norm.commitments, rev)
      me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    // Periodic fee updates
    case (norm: NormalData, CMDFeerate(rate), NORMAL)
      // GUARD: we only send fee updates if the fee gap between nodes is large enough
      if LNParams.shouldUpdateFee(norm.commitments.localCommit.spec.feeratePerKw, rate) =>
      val c1 = Commitments.sendFee(norm.commitments, rate)
      me stayWith norm.copy(commitments = c1)
      doProcess(CMDCommitSig)


    // Periodic watch for timed-out outgoing HTLCs
    case (norm: NormalData, CMDDepth(confirmationCount), NORMAL)
      if Commitments.hasTimedoutOutgoingHtlcs(norm.commitments, confirmationCount) =>
      startLocalCurrentClose(norm)


    // Can only send announcement signatures when no closing is in progress and no closing sigs present
    case (norm @ NormalData(_, commitments, None, None, false), remote: AnnouncementSignatures, NORMAL) =>
      val (localNodeSig, localBitcoinSig) = Announcements.signChannelAnnouncement(remote.shortChannelId, LNParams.nodePrivateKey,
        PublicKey(norm.announce.nodeId), commitments.localParams.fundingPrivKey, commitments.remoteParams.fundingPubKey,
        LNParams.globalFeatures)

      // Let the other node announce our channel availability
      val as = AnnouncementSignatures(commitments.channelId, remote.shortChannelId, localNodeSig, localBitcoinSig)
      me stayWith norm.copy(commitments = Commitments.addUnacked(commitments, as), announced = true)


    // When initiating or receiving a shutdown message
    // we can't proceed until all local changes are cleared
    // and we can't enter negotiations util pending HTLCs are present

    case (norm: NormalData, CMDShutdown, NORMAL)
      // GUARD: postpone shutdown if we have changes
      if me canSendCommitSig norm.commitments =>
      doProcess(CMDCommitSig)
      doProcess(CMDShutdown)


    case (norm: NormalData, CMDShutdown, NORMAL) =>
      initiateShutdown(norm)


    case (norm: NormalData, _: Shutdown, NORMAL)
      // GUARD: can't accept shutdown with unacked changes
      if norm.commitments.remoteChanges.proposed.nonEmpty =>
      throw ChannelException(CHANNEL_CLOSE_PENDING_CHANGES)


    case (norm: NormalData, remote: Shutdown, NORMAL)
      // GUARD: postpone our reply if we have changes
      if me canSendCommitSig norm.commitments =>
      doProcess(CMDCommitSig)
      doProcess(remote)


    // We have not yet send or received a shutdown so send it and retry
    case (norm @ NormalData(_, _, None, None, _), remote: Shutdown, NORMAL) =>
      initiateShutdown(norm)
      doProcess(remote)


    // We have already sent a shutdown (initially or in response to their shutdown)
    case (norm @ NormalData(announce, commitments, Some(local), None, _), remote: Shutdown, NORMAL) =>
      if (Commitments hasNoPendingHtlcs commitments) startNegotiations(announce, commitments, local, remote)
      else me stayWith norm.copy(remoteShutdown = Some apply remote)


    // Unilateral channel closing in NORMAL (bilateral is handled above)
    case (norm: NormalData, _: Error, NORMAL) => startLocalCurrentClose(norm)
    case (norm: NormalData, CMDFundingSpent(tx), NORMAL) => defineClosingAction(norm, tx)


    // NEGOTIATIONS MODE


    // At this point commitments.unackedMessages may contain:
    // - Shutdown, but it is acknowledged since we just received ClosingSigned
    // - ClosingSigned, but they are never acknowledged and spec says we only need to re-send the last one
    // this means that we can just set commitments.unackedMessages to the last sent ClosingSigned

    case (neg: NegotiationsData, ClosingSigned(_, feeSatoshis, remoteSig), NEGOTIATIONS) =>
      val Seq(closeFeeSat, remoteFeeSat) = Seq(neg.localClosingSigned.feeSatoshis, feeSatoshis) map Satoshi
      val Seq(localScript, remoteScript) = Seq(neg.localShutdown.scriptPubKey, neg.remoteShutdown.scriptPubKey)
      val closeTxOpt = Closing.checkClosingSignature(neg.commitments, localScript, remoteScript, remoteFeeSat, remoteSig)

      lazy val nextCloseFee = Closing.nextClosingFee(closeFeeSat, remoteFeeSat)
      lazy val (_, nextLocalClosingSigned) = Closing.makeClosing(neg.commitments,
        localScript, remoteScript, nextCloseFee)

      closeTxOpt match {
        case None => throw ChannelException(CHANNEL_CLOSE_SIG_FAIL)
        case Some(tx) if closeFeeSat == remoteFeeSat => startMutualClose(neg, tx, neg.localClosingSigned)
        case Some(tx) if nextCloseFee == remoteFeeSat => startMutualClose(neg, tx, nextLocalClosingSigned)
        case Some(tx) =>
          // Continue negotiations...
          val c1 = Commitments.addUnacked(neg.commitments, nextLocalClosingSigned)
          me stayWith neg.copy(commitments = c1, localClosingSigned = nextLocalClosingSigned)
      }


    // Channel closing in NEGOTIATIONS
    case (neg: NegotiationsData, _: Error, NEGOTIATIONS) => startLocalCurrentClose(neg)
    case (neg: NegotiationsData, CMDShutdown, NEGOTIATIONS) => startLocalCurrentClose(neg)


    case (neg: NegotiationsData, CMDFundingSpent(tx), NEGOTIATIONS) =>
      val (closeTx, signed) = Closing.makeClosing(neg.commitments, neg.localShutdown.scriptPubKey,
        neg.remoteShutdown.scriptPubKey, closingFee = Satoshi apply neg.localClosingSigned.feeSatoshis)

      // Happens when we agreed on a closeSig, but we don't know it yet
      // we receive the a tx notification before their ClosingSigned arrives
      if (closeTx.tx.txid == tx.txid) startMutualClose(neg, closeTx.tx, signed)
      else defineClosingAction(neg, tx)


    // Channel closing evenis in CLOSING phase
    // These spends have already been taken care of
    case (closing: ClosingData, CMDFundingSpent(tx), CLOSING)
      if closing.nextRemoteCommit.map(_.commitTx.txid).contains(tx.txid) ||
        closing.remoteCommit.map(_.commitTx.txid).contains(tx.txid) ||
        closing.localCommit.map(_.commitTx.txid).contains(tx.txid) ||
        closing.mutualClose.map(_.txid).contains(tx.txid) =>
        // Do nothing


    // Some other type of tx has been spent while we await for confirmations
    case (closing: ClosingData, CMDFundingSpent(tx), CLOSING) => defineClosingAction(closing, tx)
    case (closing: ClosingData, CMDClosingFinished, CLOSING) => become(closing, state1 = FINISHED)
    case (_, cmd: CMDAddHtlc, _) => throw DetailedException(HTLC_WRONG_CHANNEL_STATE, cmd)


    case _ =>
      // Let know if received an unhandled message
      Tools log s"Channel: unhandled $data : $change"
  }

  private def canSendCommitSig(cs: Commitments): Boolean =
    Commitments.localHasChanges(cs) && cs.remoteNextCommitInfo.isRight

  private def makeFundingLocked(cs: Commitments) = {
    val nextPerCommitmentPoint = Generators.perCommitPoint(cs.localParams.shaSeed, index = 1)
    FundingLocked(cs.channelId, nextPerCommitmentPoint = nextPerCommitmentPoint)
  }

  // We keep sending FundingLocked on reconnects until
  private def becomeNormal(wait: WaitFundingConfirmedData, our: FundingLocked, theirNextPoint: Point) = {
    val c1 = wait.commitments.copy(unackedMessages = Vector(our), remoteNextCommitInfo = Right apply theirNextPoint)
    become(NormalData(wait.announce, c1, None, None, announced = false), state1 = NORMAL)
  }

  private def initiateShutdown(norm: NormalData) = {
    val local = Shutdown(norm.commitments.channelId, norm.commitments.localParams.defaultFinalScriptPubKey)
    me stayWith norm.copy(commitments = Commitments.addUnacked(norm.commitments, local), localShutdown = Some apply local)
  }

  private def startNegotiations(announce: NodeAnnouncement, cs: Commitments, local: Shutdown, remote: Shutdown) = {
    val firstSigned = Closing.makeFirstClosing(cs, local.scriptPubKey, remote.scriptPubKey, cs.localCommit.spec.feeratePerKw)
    become(NegotiationsData(announce, Commitments.addUnacked(cs, firstSigned), firstSigned, local, remote), state1 = NEGOTIATIONS)
  }

  private def startMutualClose(neg: NegotiationsData, closeTx: Transaction, signed: ClosingSigned) = {
    // Negotiations completed successfully and we can broadcast a mutual closing transaction while entering a CLOSING state
    val closing = ClosingData(neg.announce, Commitments.addUnacked(neg.commitments, signed), mutualClose = closeTx :: Nil)
    become(closing, state1 = CLOSING)
  }

  private def startLocalCurrentClose(some: ChannelData with HasCommitments) = {
    // Something went wrong and we decided to spend our current commit transaction
    val commitTx: Transaction = some.commitments.localCommit.publishableTxs.commitTx.tx
    Closing.claimCurrentLocalCommitTxOutputs(some.commitments, commitTx, LNParams.bag) -> some match {
      case (claim: LocalCommitPublished, closing: ClosingData) => become(closing.copy(localCommit = claim :: Nil), CLOSING)
      case (claim, _) => become(ClosingData(some.announce, some.commitments, localCommit = claim :: Nil), CLOSING)
    }
  }

  private def startRemoteCurrentClose(some: ChannelData with HasCommitments, commitTx: Transaction) =
    // Something went wrong on their side and they decided to spend their CURRENT commit tx, we need to take what's ours
    Closing.claimRemoteCommitTxOutputs(some.commitments, some.commitments.remoteCommit, commitTx, LNParams.bag) -> some match {
      case (claim: RemoteCommitPublished, closing: ClosingData) => become(data1 = closing.copy(remoteCommit = claim :: Nil), CLOSING)
      case (claim, _) => become(ClosingData(some.announce, some.commitments, remoteCommit = claim :: Nil), CLOSING)
    }

  private def startRemoteNextClose(some: ChannelData with HasCommitments, commitTx: Transaction, nextRemoteCommit: RemoteCommit) =
    // Something went wrong on their side and they decided to spend their NEXT commit transaction, we still need to take ours
    Closing.claimRemoteCommitTxOutputs(some.commitments, nextRemoteCommit, commitTx, LNParams.bag) -> some match {
      case (claim: RemoteCommitPublished, closing: ClosingData) => become(closing.copy(nextRemoteCommit = claim :: Nil), CLOSING)
      case (claim, _) => become(ClosingData(some.announce, some.commitments, nextRemoteCommit = claim :: Nil), CLOSING)
    }

  private def startRemoteOther(some: ChannelData with HasCommitments, commitTx: Transaction) =
    // This is a contract breach, they have spent a revoked transaction so we can take all the money
    Closing.claimRevokedRemoteCommitTxOutputs(commitments = some.commitments, commitTx) -> some match {
      case (Some(claim), closing: ClosingData) => become(closing.modify(_.revokedCommits).using(claim +: _), CLOSING)
      case (Some(claim), _) => become(ClosingData(some.announce, some.commitments, revokedCommits = claim :: Nil), CLOSING)
      case (None, _) => startLocalCurrentClose(some) // Info leak, try to spend current commit
    }

  private def defineClosingAction(some: ChannelData with HasCommitments, tx: Transaction) =
    some.commitments.remoteNextCommitInfo.left.map(waiting => waiting.nextRemoteCommit) match {
      case Left(remoteCommit) if remoteCommit.txid == tx.txid => startRemoteNextClose(some, tx, remoteCommit)
      case _ if some.commitments.remoteCommit.txid == tx.txid => startRemoteCurrentClose(some, tx)
      case _ => startRemoteOther(some, tx)
    }
}

object Channel {
  // Channel states
  val WAIT_FOR_INIT = "WaitForInit"
  val WAIT_FOR_ACCEPT = "WaitForAccept"
  val WAIT_FOR_FUNDING = "WaitForFunding"
  val WAIT_FUNDING_SIGNED = "WaitFundingSigned"
  val WAIT_FUNDING_DONE = "WaitFundingDone"
  val NEGOTIATIONS = "Negotiations"
  val FINISHED = "Finished"
  val CLOSING = "Closing"
  val NORMAL = "Normal"
}