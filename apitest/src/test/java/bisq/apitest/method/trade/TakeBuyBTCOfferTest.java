/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.apitest.method.trade;

import bisq.core.payment.PaymentAccount;

import bisq.proto.grpc.OfferInfo;

import io.grpc.StatusRuntimeException;

import java.util.List;
import java.util.concurrent.TimeoutException;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import static bisq.apitest.config.ApiTestConfig.BSQ;
import static bisq.apitest.config.ApiTestConfig.USD;
import static bisq.core.trade.model.bisq_v1.Trade.Phase.PAYOUT_PUBLISHED;
import static bisq.core.trade.model.bisq_v1.Trade.State.BUYER_RECEIVED_PAYOUT_TX_PUBLISHED_MSG;
import static bisq.core.trade.model.bisq_v1.Trade.State.SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static protobuf.OfferDirection.BUY;
import static protobuf.OpenOffer.State.AVAILABLE;

@Disabled
@SuppressWarnings("ConstantConditions")
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TakeBuyBTCOfferTest extends AbstractTradeTest {

    // Alice is maker/buyer, Bob is taker/seller.

    // Maker and Taker fees are in BSQ.
    private static final String TRADE_FEE_CURRENCY_CODE = BSQ;

    @Test
    @Order(1)
    public void testTakeAlicesBuyOffer(final TestInfo testInfo) {
        try {
            PaymentAccount alicesUsdAccount = createDummyF2FAccount(aliceClient, "US");
            var alicesOffer = aliceClient.createMarketBasedPricedOffer(BUY.name(),
                    USD,
                    10_000_000L,
                    10_000_000L, // min-amount = amount
                    0.00,
                    defaultBuyerSecurityDepositPct.get(),
                    alicesUsdAccount.getId(),
                    TRADE_FEE_CURRENCY_CODE,
                    NO_TRIGGER_PRICE);
            var offerId = alicesOffer.getId();
            assertFalse(alicesOffer.getIsCurrencyForMakerFeeBtc());

            // Wait for Alice's AddToOfferBook task.
            // Wait times vary;  my logs show >= 2-second delay.
            var timeout = System.currentTimeMillis() + 3000;
            while (bobClient.getOffersSortedByDate(USD).size() < 1) {
                sleep(100);
                if (System.currentTimeMillis() > timeout)
                    fail(new TimeoutException("Timed out waiting for Offer to be added to OfferBook"));
            }

            List<OfferInfo> alicesUsdOffers = aliceClient.getMyOffersSortedByDate(BUY.name(), USD);
            assertEquals(1, alicesUsdOffers.size());

            PaymentAccount bobsUsdAccount = createDummyF2FAccount(bobClient, "US");
            var ignoredTakeOfferAmountParam = 0L;

            var trade = takeAlicesOffer(offerId,
                    bobsUsdAccount.getId(),
                    TRADE_FEE_CURRENCY_CODE,
                    ignoredTakeOfferAmountParam,
                    false);

            // Allow available offer to be removed from offer book.
            timeout = System.currentTimeMillis() + 2500;
            do {
                alicesUsdOffers = aliceClient.getMyOffersSortedByDate(BUY.name(), USD);
                sleep(100);
                if (System.currentTimeMillis() > timeout)
                    fail(new TimeoutException("Timed out waiting for Offer to be removed from OfferBook"));
            } while (alicesUsdOffers.size() > 0);

            assertEquals(0, alicesUsdOffers.size());

            trade = bobClient.getTrade(tradeId);
            assertEquals(alicesOffer.getAmount(), trade.getTradeAmountAsLong());
            verifyTakerDepositNotConfirmed(trade);
            logTrade(log, testInfo, "Alice's Maker/Buyer View", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Seller View", bobClient.getTrade(tradeId));
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(2)
    public void testPaymentMessagingPreconditions(final TestInfo testInfo) {
        try {
            // Alice is maker / btc buyer, Bob is taker / btc seller.
            // Verify payment sent and rcvd msgs are sent by the right peers:  buyer and seller.
            verifyPaymentSentMsgIsFromBtcBuyerPrecondition(log, bobClient);
            verifyPaymentReceivedMsgIsFromBtcSellerPrecondition(log, aliceClient);

            // Verify fiat payment sent and rcvd msgs cannot be sent before trade deposit tx is confirmed.
            verifyPaymentSentMsgDepositTxConfirmedPrecondition(log, aliceClient);
            verifyPaymentReceivedMsgDepositTxConfirmedPrecondition(log, bobClient);

            // Now generate the BTC block to confirm the taker deposit tx.
            genBtcBlocksThenWait(1, 2_500);
            waitForTakerDepositConfirmation(log, testInfo, bobClient, tradeId);

            // Verify the seller can only send a payment rcvd msg after the payment started msg.
            verifyPaymentReceivedMsgAfterPaymentSentMsgPrecondition(log, bobClient);
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(3)
    public void testAlicesConfirmPaymentStarted(final TestInfo testInfo) {
        try {
            var trade = aliceClient.getTrade(tradeId);
            waitForTakerDepositConfirmation(log, testInfo, aliceClient, trade.getTradeId());
            aliceClient.confirmPaymentStarted(trade.getTradeId());
            sleep(6_000);
            waitUntilBuyerSeesPaymentStartedMessage(log, testInfo, aliceClient, tradeId);
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(4)
    public void testBobsConfirmPaymentReceived(final TestInfo testInfo) {
        try {
            waitUntilSellerSeesPaymentStartedMessage(log, testInfo, bobClient, tradeId);
            var trade = bobClient.getTrade(tradeId);
            bobClient.confirmPaymentReceived(trade.getTradeId());
            sleep(3_000);
            trade = bobClient.getTrade(tradeId);
            // Note: offer.state == available
            assertEquals(AVAILABLE.name(), trade.getOffer().getState());
            EXPECTED_PROTOCOL_STATUS.setState(SELLER_SAW_ARRIVED_PAYOUT_TX_PUBLISHED_MSG)
                    .setPhase(PAYOUT_PUBLISHED)
                    .setPayoutPublished(true)
                    .setPaymentReceivedMessageSent(true);
            verifyExpectedProtocolStatus(trade);
            logTrade(log, testInfo, "Bob's view after confirming fiat payment received", trade);
        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }

    @Test
    @Order(5)
    public void testCloseTrade(final TestInfo testInfo) {
        try {
            genBtcBlocksThenWait(1, 1_000);
            var trade = aliceClient.getTrade(tradeId);
            logTrade(log, testInfo, "Alice's view before closing trade and keeping funds", trade);
            aliceClient.closeTrade(tradeId);
            bobClient.closeTrade(tradeId);
            genBtcBlocksThenWait(1, 1_000);
            trade = aliceClient.getTrade(tradeId);
            EXPECTED_PROTOCOL_STATUS.setState(BUYER_RECEIVED_PAYOUT_TX_PUBLISHED_MSG).setPhase(PAYOUT_PUBLISHED);
            verifyExpectedProtocolStatus(trade);
            logTrade(log, testInfo, "Alice's Maker/Buyer View (Done)", aliceClient.getTrade(tradeId));
            logTrade(log, testInfo, "Bob's Taker/Seller View (Done)", bobClient.getTrade(tradeId));
            logBalances(log, testInfo);

            runCliGetClosedTrades();

        } catch (StatusRuntimeException e) {
            fail(e);
        }
    }
}
