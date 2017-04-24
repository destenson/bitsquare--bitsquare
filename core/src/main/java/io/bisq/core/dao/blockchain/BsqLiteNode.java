/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.dao.blockchain;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;
import io.bisq.common.UserThread;
import io.bisq.common.handlers.ErrorMessageHandler;
import io.bisq.common.network.Msg;
import io.bisq.common.util.Utilities;
import io.bisq.core.dao.blockchain.exceptions.BlockNotConnectingException;
import io.bisq.core.dao.blockchain.p2p.GetBsqBlocksRequest;
import io.bisq.core.dao.blockchain.p2p.GetBsqBlocksResponse;
import io.bisq.core.dao.blockchain.p2p.NewBsqBlockBroadcastMsg;
import io.bisq.core.dao.blockchain.parse.BsqChainState;
import io.bisq.core.dao.blockchain.parse.BsqLiteNodeExecutor;
import io.bisq.core.dao.blockchain.parse.BsqParser;
import io.bisq.core.dao.blockchain.vo.BsqBlock;
import io.bisq.core.provider.fee.FeeService;
import io.bisq.network.p2p.NodeAddress;
import io.bisq.network.p2p.P2PService;
import io.bisq.network.p2p.network.Connection;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

// We are in UserThread context. We get callbacks from threaded classes which are already mapped to the UserThread.
@Slf4j
public class BsqLiteNode extends BsqNode {
    private BsqLiteNodeExecutor bsqLiteNodeExecutor;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @SuppressWarnings("WeakerAccess")
    @Inject
    public BsqLiteNode(P2PService p2PService,
                       BsqParser bsqParser,
                       BsqLiteNodeExecutor bsqLiteNodeExecutor,
                       BsqChainState bsqChainState,
                       FeeService feeService) {
        super(p2PService,
                bsqParser,
                bsqChainState,
                feeService);
        this.bsqLiteNodeExecutor = bsqLiteNodeExecutor;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onAllServicesInitialized(ErrorMessageHandler errorMessageHandler) {
        super.onAllServicesInitialized(errorMessageHandler);
    }

    @Override
    protected void onP2PNetworkReady() {
        super.onP2PNetworkReady();

        p2PService.getNetworkNode().addMessageListener(this::onMessage);
        // delay a bit to not stress too much at startup
        UserThread.runAfter(this::startParseBlocks, 2);
    }

    @Override
    protected void parseBlocksWithChainHeadHeight(int startBlockHeight, int genesisBlockHeight, String genesisTxId) {
        parseBlocks(startBlockHeight, genesisBlockHeight, genesisTxId, 0);
    }

    @Override
    protected void parseBlocks(int startBlockHeight, int genesisBlockHeight, String genesisTxId, Integer chainHeadHeight) {
        // TODO use handler class
        //RequestBsqBlocksHandler requestBsqBlocksHandler = new RequestBsqBlocksHandler(p2PService.getNetworkNode());
        NodeAddress peersNodeAddress = p2PService.getSeedNodeAddresses().stream().findFirst().get();

        GetBsqBlocksRequest getBsqBlocksRequest = new GetBsqBlocksRequest(startBlockHeight);
        log.info("sendMessage " + getBsqBlocksRequest + " to " + peersNodeAddress);
        SettableFuture<Connection> future = p2PService.getNetworkNode().sendMessage(peersNodeAddress, getBsqBlocksRequest);
        Futures.addCallback(future, new FutureCallback<Connection>() {
            @Override
            public void onSuccess(Connection connection) {
                log.info("onSuccess Send " + getBsqBlocksRequest + " to " + peersNodeAddress + " succeeded.");
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                log.error(throwable.toString());
            }
        });
        
        
       /* 
        requestBsqBlocksHandler.request(peersNodeAddress, startBlockHeight, bsqBlockList -> {
            try {
                bsqParser.parseBsqBlocks(bsqBlockList, getGenesisBlockHeight(), getGenesisTxId(),
                        this::onNewBsqBlock);
            } catch (BsqBlockchainException e) {
                e.printStackTrace();
            } catch (BlockNotConnectingException e) {
                e.printStackTrace();
            }
            onParseBlockchainComplete(genesisBlockHeight, genesisTxId);
        });*/
    }

    // TODO use handler class
    private void onMessage(Msg msg, Connection connection) {
        if (msg instanceof GetBsqBlocksResponse && connection.getPeersNodeAddressOptional().isPresent()) {
            GetBsqBlocksResponse getBsqBlocksResponse = (GetBsqBlocksResponse) msg;
            byte[] bsqBlocksBytes = getBsqBlocksResponse.getBsqBlocksBytes();
            List<BsqBlock> bsqBlockList = Utilities.<ArrayList<BsqBlock>>deserialize(bsqBlocksBytes);
            log.info("received msg with {} items", bsqBlockList.size());
            // Be safe and reset all mutable data in case the provider would not have done it
            bsqBlockList.stream().forEach(BsqBlock::reset);
            bsqLiteNodeExecutor.parseBsqBlocksForLiteNode(bsqBlockList,
                    genesisBlockHeight,
                    genesisTxId,
                    this::onNewBsqBlock,
                    () -> {
                        onParseBlockchainComplete(genesisBlockHeight, genesisTxId);
                    }, throwable -> {
                        if (throwable instanceof BlockNotConnectingException) {
                            startReOrgFromLastSnapshot();
                        } else {
                            log.error(throwable.toString());
                            throwable.printStackTrace();
                        }
                    });
        } else if (parseBlockchainComplete && msg instanceof NewBsqBlockBroadcastMsg) {
            NewBsqBlockBroadcastMsg newBsqBlockBroadcastMsg = (NewBsqBlockBroadcastMsg) msg;
            byte[] bsqBlockBytes = newBsqBlockBroadcastMsg.getBsqBlockBytes();
            BsqBlock bsqBlock = Utilities.<BsqBlock>deserialize(bsqBlockBytes);
            // Be safe and reset all mutable data in case the provider would not have done it
            bsqBlock.reset();
            log.info("received broadcastNewBsqBlock bsqBlock {}", bsqBlock);
            if (!bsqChainState.containsBlock(bsqBlock)) {
                bsqLiteNodeExecutor.parseBsqBlockForLiteNode(bsqBlock,
                        genesisBlockHeight,
                        genesisTxId,
                        () -> {
                            onNewBsqBlock(bsqBlock);
                        }, throwable -> {
                            if (throwable instanceof BlockNotConnectingException) {
                                startReOrgFromLastSnapshot();
                            } else {
                                log.error(throwable.toString());
                                throwable.printStackTrace();
                            }
                        });
            }
        }
    }

    @Override
    protected void onParseBlockchainComplete(int genesisBlockHeight, String genesisTxId) {
        parseBlockchainComplete = true;
        bsqChainStateListeners.stream().forEach(BsqChainStateListener::onBsqChainStateChanged);
    }
}
