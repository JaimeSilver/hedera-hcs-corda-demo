package com.hedera.hashgraph.hcs_demo.notary;

import com.hedera.hashgraph.sdk.Client;
import com.hedera.hashgraph.sdk.Hbar;
import com.hedera.hashgraph.sdk.account.CryptoTransferTransaction;
import com.hedera.hashgraph.sdk.HederaStatusException;
import com.hedera.hashgraph.sdk.Transaction;
import com.hedera.hashgraph.sdk.TransactionId;
import com.hedera.hashgraph.sdk.account.AccountId;
import com.hedera.hashgraph.sdk.consensus.ConsensusMessageSubmitTransaction;
import com.hedera.hashgraph.sdk.consensus.ConsensusTopicId;
import com.hedera.hashgraph.sdk.mirror.MirrorClient;
import com.hedera.hashgraph.sdk.mirror.MirrorConsensusTopicQuery;
import com.hedera.hashgraph.sdk.mirror.MirrorConsensusTopicResponse;
import com.hedera.hashgraph.sdk.mirror.MirrorSubscriptionHandle;

import net.corda.core.contracts.StateRef;
import net.corda.core.crypto.Crypto;
import net.corda.core.crypto.SecureHash;
import net.corda.core.crypto.SignableData;
import net.corda.core.crypto.SignatureMetadata;
import net.corda.core.crypto.TransactionSignature;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.FlowSession;
import net.corda.core.flows.NotaryError;
import net.corda.core.flows.NotaryException;
import net.corda.core.flows.StateConsumptionDetails;
import net.corda.core.internal.notary.NotaryService;
import net.corda.core.node.ServiceHub;
import net.corda.core.transactions.CoreTransaction;
import net.corda.node.services.api.ServiceHubInternal;
import net.corda.node.services.config.NotaryConfig;

import org.jetbrains.annotations.NotNull;

import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

public class HcsNotaryService extends NotaryService {
    private final ServiceHubInternal serviceHubInternal;
    private final PublicKey publicKey;
    private final NotaryConfig notaryConfig;

    private final Client sdkClient;
    private final MirrorClient mirrorClient;

    private static final ConsensusTopicId topicId = new ConsensusTopicId(93030);
    private static final AccountId operatorAccountId = new AccountId(9473);

    //**** Start of Modification *******
    private static final AccountId receipientId = AccountId.fromString("0.0.106641");
    //****   End of Modification *******

    private final ConcurrentHashMap<StateRef, StateDestruction> stateDestructions = new ConcurrentHashMap<>();

    private long sequenceNumber = -1;

    @Nullable
    private MirrorSubscriptionHandle subscriptionHandle;

    public HcsNotaryService(ServiceHubInternal serviceHubInternal, PublicKey publicKey) {
        super();
        this.serviceHubInternal = serviceHubInternal;
        this.publicKey = publicKey;
        this.notaryConfig = serviceHubInternal.getConfiguration().getNotary();

        sdkClient = Client.forTestnet()
            .setOperatorWith(
                    operatorAccountId,
                    SigningUtils.publicKey,
                    message -> SigningUtils.sign(SigningUtils.privateKeyBytes, message));

        mirrorClient = new MirrorClient("hcs.testnet.mirrornode.hedera.com:5600");
    }

    @NotNull
    @Override
    public PublicKey getNotaryIdentityKey() {
        return publicKey;
    }

    @NotNull
    @Override
    public ServiceHub getServices() {
        return serviceHubInternal;
    }

    @NotNull
    @Override
    public FlowLogic<Void> createServiceFlow(@NotNull FlowSession otherPartySession) {
        final Duration eta = Duration.ofSeconds(notaryConfig.getEtaMessageThresholdSeconds());

        return new HcsNotaryServiceFlow(this, otherPartySession);
    }

    long submitTransactionSpends(CoreTransaction transaction) throws HederaStatusException {
        System.out.println("submitting transaction spends");

        ConsensusMessageSubmitTransaction msgTxn = new ConsensusMessageSubmitTransaction()
                .setTopicId(topicId);

        System.out.println("serializing corda transaction");

        msgTxn.setMessage(new SerializeTransaction(transaction).serialize());

        System.out.println("building transaction");

        //********* Start of Modification ****************
        System.out.println("submitting HBar transaction to Hedera");
        try {
            Hbar amount = Hbar.fromTinybar(10_000);
            CryptoTransferTransaction hbarTxn = new CryptoTransferTransaction()
                    .addSender(operatorAccountId, amount)
                    .addRecipient(receipientId, amount)
                    .setTransactionMemo("HBAR test form CORDA");

            Transaction hederaTxn2 = hbarTxn.build(sdkClient);
            TransactionId hbarId = hederaTxn2
                .signWith(SigningUtils.submitPublicKey, m -> SigningUtils.sign(SigningUtils.submitKeyBytes, m))
                .execute(sdkClient);
            System.out.println("Hbar transfer transaction ID: " + hbarId);
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //********* End of Modification ****************

        Transaction hederaTxn = msgTxn.build(sdkClient);

        System.out.println("submitting HCS transaction to Hedera");

        TransactionId txnId = hederaTxn
                .signWith(SigningUtils.submitPublicKey, m -> SigningUtils.sign(SigningUtils.submitKeyBytes, m))
                .execute(sdkClient);

        System.out.println("transaction ID" + txnId);

        return txnId.getReceipt(sdkClient)
                .getConsensusTopicSequenceNumber();

    }

    boolean checkTransaction(CoreTransaction txn, long sequenceNumber) throws NotaryException {
        if (this.sequenceNumber < sequenceNumber) {
            return false;
        }

        HashMap<StateRef, StateConsumptionDetails> consumedStates = new HashMap<>();

        for (StateRef input : txn.getInputs()) {
            StateDestruction destruction = stateDestructions.get(input);

            if (destruction != null && !destruction.txnId.equals(txn.getId())) {
                consumedStates.put(input,
                        new StateConsumptionDetails(destruction.txnId, StateConsumptionDetails.ConsumedStateType.INPUT_STATE));
            }
        }

        for (StateRef ref : txn.getReferences()) {
            StateDestruction destruction = stateDestructions.get(ref);

            if (destruction != null) {
                consumedStates.put(ref,
                        new StateConsumptionDetails(destruction.txnId, StateConsumptionDetails.ConsumedStateType.REFERENCE_INPUT_STATE));
            }
        }

        if (!consumedStates.isEmpty()) {
            System.out.println("throwing error, consumed states: " + consumedStates);
            throw new NotaryException(new NotaryError.Conflict(txn.getId(), consumedStates), txn.getId());
        }

        return true;
    }

    TransactionSignature signTransaction(SecureHash txId) {
        SignableData signableData = new SignableData(txId, new SignatureMetadata(serviceHubInternal.getMyInfo().getPlatformVersion(), Crypto.findSignatureScheme(publicKey).getSchemeNumberID()));
        return serviceHubInternal.getKeyManagementService().sign(signableData, publicKey);
    }


    private void onMessage(MirrorConsensusTopicResponse msg) {
        System.out.println("received consensus message " + msg);

        SerializeTransaction txn = SerializeTransaction.deserialize(msg.message);

        System.out.println("received transaction " + txn);

        for (StateRef input : txn.inputs) {
            // don't overwrite with duplicate destructions
            stateDestructions.computeIfAbsent(input, key -> new StateDestruction(txn.txnId, msg.sequenceNumber));
        }

        sequenceNumber = msg.sequenceNumber;
    }

    @Override
    public void start() {
        subscriptionHandle = new MirrorConsensusTopicQuery()
                .setTopicId(topicId)
                // for demo purposes we don't care about any states before the notary started
                .setStartTime(Instant.now())
                .subscribe(
                        mirrorClient,
                        this::onMessage,
                        e -> System.out.println("err: " + e)
                );
    }

    @Override
    public void stop() {
        if (subscriptionHandle != null) {
            subscriptionHandle.unsubscribe();
        }
    }
}
