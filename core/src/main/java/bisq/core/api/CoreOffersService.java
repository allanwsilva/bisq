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

package bisq.core.api;

import bisq.core.api.exception.NotFoundException;
import bisq.core.monetary.Altcoin;
import bisq.core.monetary.Price;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferBookService;
import bisq.core.offer.OfferDirection;
import bisq.core.offer.OfferFilterService;
import bisq.core.offer.OfferUtil;
import bisq.core.offer.OpenOffer;
import bisq.core.offer.OpenOfferManager;
import bisq.core.offer.bisq_v1.CreateOfferService;
import bisq.core.offer.bisq_v1.MutableOfferPayloadFields;
import bisq.core.offer.bisq_v1.OfferPayload;
import bisq.core.offer.bsq_swap.OpenBsqSwapOfferService;
import bisq.core.payment.PaymentAccount;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.user.User;

import bisq.common.crypto.KeyRing;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.utils.Fiat;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.math.BigDecimal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import static bisq.common.util.MathUtils.exactMultiply;
import static bisq.common.util.MathUtils.roundDoubleToLong;
import static bisq.common.util.MathUtils.scaleUpByPowerOf10;
import static bisq.core.locale.CurrencyUtil.apiSupportsCryptoCurrency;
import static bisq.core.locale.CurrencyUtil.isCryptoCurrency;
import static bisq.core.locale.CurrencyUtil.isFiatCurrency;
import static bisq.core.offer.Offer.State;
import static bisq.core.offer.OfferDirection.BUY;
import static bisq.core.offer.OfferDirection.SELL;
import static bisq.core.offer.OfferUtil.getRandomOfferId;
import static bisq.core.offer.OpenOffer.State.AVAILABLE;
import static bisq.core.offer.OpenOffer.State.DEACTIVATED;
import static bisq.core.payment.PaymentAccountUtil.isPaymentAccountValidForOffer;
import static bisq.core.util.PriceUtil.getMarketPriceAsLong;
import static bisq.proto.grpc.EditOfferRequest.EditType;
import static bisq.proto.grpc.EditOfferRequest.EditType.FIXED_PRICE_AND_ACTIVATION_STATE;
import static bisq.proto.grpc.EditOfferRequest.EditType.FIXED_PRICE_ONLY;
import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

@Singleton
@Slf4j
class CoreOffersService {

    private final Supplier<Comparator<Offer>> priceComparator = () ->
            comparing(Offer::getPrice);

    private final Supplier<Comparator<OpenOffer>> openOfferPriceComparator = () ->
            comparing(openOffer -> openOffer.getOffer().getPrice());

    private final BiFunction<String, Boolean, Offer> toOfferWithId = (id, isMyOffer) ->
            isMyOffer ? getMyOffer(id).getOffer() : getOffer(id);

    private final CoreContext coreContext;
    private final KeyRing keyRing;
    // Dependencies on core api services in this package must be kept to an absolute
    // minimum, but some trading functions require an unlocked wallet's key, so an
    // exception is made in this case.
    private final CoreWalletsService coreWalletsService;
    private final CreateOfferService createOfferService;
    private final OfferBookService offerBookService;
    private final OfferFilterService offerFilterService;
    private final OpenOfferManager openOfferManager;
    private final OpenBsqSwapOfferService openBsqSwapOfferService;
    private final OfferUtil offerUtil;
    private final PriceFeedService priceFeedService;
    private final User user;

    @Inject
    public CoreOffersService(CoreContext coreContext,
                             KeyRing keyRing,
                             CoreWalletsService coreWalletsService,
                             CreateOfferService createOfferService,
                             OfferBookService offerBookService,
                             OfferFilterService offerFilterService,
                             OpenOfferManager openOfferManager,
                             OpenBsqSwapOfferService openBsqSwapOfferService,
                             OfferUtil offerUtil,
                             PriceFeedService priceFeedService,
                             User user) {
        this.coreContext = coreContext;
        this.keyRing = keyRing;
        this.coreWalletsService = coreWalletsService;
        this.createOfferService = createOfferService;
        this.offerBookService = offerBookService;
        this.offerFilterService = offerFilterService;
        this.openOfferManager = openOfferManager;
        this.openBsqSwapOfferService = openBsqSwapOfferService;
        this.offerUtil = offerUtil;
        this.priceFeedService = priceFeedService;
        this.user = user;
    }

    boolean isFiatOffer(String id, boolean isMyOffer) {
        var offer = toOfferWithId.apply(id, isMyOffer);
        return OfferUtil.isFiatOffer(offer);
    }

    boolean isAltcoinOffer(String id, boolean isMyOffer) {
        var offer = toOfferWithId.apply(id, isMyOffer);
        return OfferUtil.isAltcoinOffer(offer);
    }

    boolean isBsqSwapOffer(String id, boolean isMyOffer) {
        var offer = toOfferWithId.apply(id, isMyOffer);
        return offer.isBsqSwapOffer();
    }

    Offer getOffer(String id) {
        return findAvailableOffer(id).orElseThrow(() ->
                new NotFoundException(format("offer with id '%s' not found", id)));
    }

    Optional<Offer> findAvailableOffer(String id) {
        return offerBookService.getOffers().stream()
                .filter(o -> o.getId().equals(id))
                .filter(o -> !o.isMyOffer(keyRing))
                .filter(o -> offerFilterService.canTakeOffer(o, coreContext.isApiUser()).isValid())
                .findAny();
    }

    OpenOffer getMyOffer(String id) {
        return findMyOpenOffer(id).orElseThrow(() ->
                new NotFoundException(format("offer with id '%s' not found", id)));
    }

    Optional<OpenOffer> findMyOpenOffer(String id) {
        return openOfferManager.getObservableList().stream()
                .filter(o -> o.getId().equals(id))
                .filter(o -> o.getOffer().isMyOffer(keyRing))
                .findAny();
    }

    Offer getBsqSwapOffer(String id) {
        return findAvailableBsqSwapOffer(id).orElseThrow(() ->
                new NotFoundException(format("offer with id '%s' not found", id)));
    }

    Optional<Offer> findAvailableBsqSwapOffer(String id) {
        return offerBookService.getOffers().stream()
                .filter(o -> o.getId().equals(id))
                .filter(o -> !o.isMyOffer(keyRing))
                .filter(o -> offerFilterService.canTakeOffer(o, coreContext.isApiUser()).isValid())
                .filter(Offer::isBsqSwapOffer)
                .findAny();
    }

    Offer getMyBsqSwapOffer(String id) {
        return findMyBsqSwapOffer(id).orElseThrow(() ->
                new NotFoundException(format("offer with id '%s' not found", id)));
    }

    Optional<Offer> findMyBsqSwapOffer(String id) {
        return offerBookService.getOffers().stream()
                .filter(o -> o.getId().equals(id))
                .filter(o -> o.isMyOffer(keyRing))
                .filter(Offer::isBsqSwapOffer)
                .findAny();
    }

    List<Offer> getBsqSwapOffers(String direction) {
        return offerBookService.getOffers().stream()
                .filter(o -> !o.isMyOffer(keyRing))
                .filter(o -> o.getDirection().name().equalsIgnoreCase(direction))
                .filter(Offer::isBsqSwapOffer)
                .sorted(priceComparator(direction, false))
                .collect(Collectors.toList());
    }

    List<Offer> getOffers(String direction, String currencyCode) {
        var upperCaseCurrencyCode = currencyCode.toUpperCase();
        var isFiat = isFiatCurrency(upperCaseCurrencyCode);
        if (isFiat) {
            return offerBookService.getOffers().stream()
                    .filter(o -> offerMatchesDirectionAndCurrency(o, direction, upperCaseCurrencyCode))
                    .sorted(priceComparator(direction, true))
                    .collect(Collectors.toList());
        } else {
            // In fiat offers, the baseCurrencyCode=BTC, counterCurrencyCode=FiatCode.
            // In altcoin offers, baseCurrencyCode=AltcoinCode, counterCurrencyCode=BTC.
            // This forces an extra filtering step below:  get all BTC offers,
            // then filter on the currencyCode param (the altcoin code).
            if (apiSupportsCryptoCurrency(upperCaseCurrencyCode))
                return offerBookService.getOffers().stream()
                        .filter(o -> offerMatchesDirectionAndCurrency(o, direction, "BTC"))
                        .filter(o -> o.getBaseCurrencyCode().equalsIgnoreCase(upperCaseCurrencyCode))
                        .sorted(priceComparator(direction, false))
                        .collect(Collectors.toList());
            else
                throw new IllegalArgumentException(
                        format("api does not support the '%s' crypto currency", upperCaseCurrencyCode));
        }
    }

    List<OpenOffer> getMyOffers(String direction, String currencyCode) {
        var upperCaseCurrencyCode = currencyCode.toUpperCase();
        var isFiat = isFiatCurrency(upperCaseCurrencyCode);
        if (isFiat) {
            return openOfferManager.getObservableList().stream()
                    .filter(o -> o.getOffer().isMyOffer(keyRing))
                    .filter(o -> offerMatchesDirectionAndCurrency(o.getOffer(), direction, upperCaseCurrencyCode))
                    .sorted(openOfferPriceComparator(direction, true))
                    .collect(Collectors.toList());
        } else {
            // In fiat offers, the baseCurrencyCode=BTC, counterCurrencyCode=FiatCode.
            // In altcoin offers, baseCurrencyCode=AltcoinCode, counterCurrencyCode=BTC.
            // This forces an extra filtering step below:  get all BTC offers,
            // then filter on the currencyCode param (the altcoin code).
            if (apiSupportsCryptoCurrency(upperCaseCurrencyCode))
                return openOfferManager.getObservableList().stream()
                        .filter(o -> o.getOffer().isMyOffer(keyRing))
                        .filter(o -> offerMatchesDirectionAndCurrency(o.getOffer(), direction, "BTC"))
                        .filter(o -> o.getOffer().getBaseCurrencyCode().equalsIgnoreCase(upperCaseCurrencyCode))
                        .sorted(openOfferPriceComparator(direction, false))
                        .collect(Collectors.toList());
            else
                throw new IllegalArgumentException(
                        format("api does not support the '%s' crypto currency", upperCaseCurrencyCode));
        }
    }

    List<Offer> getMyBsqSwapOffers(String direction) {
        return offerBookService.getOffers().stream()
                .filter(o -> o.isMyOffer(keyRing))
                .filter(o -> o.getDirection().name().equalsIgnoreCase(direction))
                .filter(Offer::isBsqSwapOffer)
                .sorted(priceComparator(direction, false))
                .collect(Collectors.toList());
    }

    OpenOffer getMyOpenBsqSwapOffer(String id) {
        return openOfferManager.getOpenOfferById(id)
                .filter(open -> open.getOffer().isMyOffer(keyRing))
                .filter(open -> open.getOffer().isBsqSwapOffer())
                .orElseThrow(() ->
                        new NotFoundException(format("openoffer with id '%s' not found", id)));
    }

    OpenOffer getMyOpenOffer(String id) {
        return openOfferManager.getOpenOfferById(id)
                .filter(open -> open.getOffer().isMyOffer(keyRing))
                .orElseThrow(() ->
                        new NotFoundException(format("offer with id '%s' not found", id)));
    }

    boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    void createAndPlaceBsqSwapOffer(String directionAsString,
                                    long amountAsLong,
                                    long minAmountAsLong,
                                    String priceAsString,
                                    Consumer<Offer> resultHandler) {
        coreWalletsService.verifyWalletsAreAvailable();
        coreWalletsService.verifyEncryptedWalletIsUnlocked();

        String currencyCode = "BSQ";
        String offerId = getRandomOfferId();
        OfferDirection direction = OfferDirection.valueOf(directionAsString.toUpperCase());
        Coin amount = Coin.valueOf(amountAsLong);
        Coin minAmount = minAmountAsLong == 0 ? amount : Coin.valueOf(minAmountAsLong);
        Price price = Price.valueOf(currencyCode, priceStringToLong(priceAsString, currencyCode));
        openBsqSwapOfferService.requestNewOffer(offerId,
                direction,
                amount,
                minAmount,
                price,
                offer -> placeBsqSwapOffer(offer, () -> resultHandler.accept(offer)));
    }

    void createAndPlaceOffer(String currencyCode,
                             String directionAsString,
                             String priceAsString,
                             boolean useMarketBasedPrice,
                             double marketPriceMargin,
                             long amountAsLong,
                             long minAmountAsLong,
                             double buyerSecurityDepositPct,
                             String triggerPrice,
                             String paymentAccountId,
                             String makerFeeCurrencyCode,
                             Consumer<Offer> resultHandler) {
        coreWalletsService.verifyWalletsAreAvailable();
        coreWalletsService.verifyEncryptedWalletIsUnlocked();
        offerUtil.maybeSetFeePaymentCurrencyPreference(makerFeeCurrencyCode);

        PaymentAccount paymentAccount = user.getPaymentAccount(paymentAccountId);
        if (paymentAccount == null)
            throw new IllegalArgumentException(format("payment account with id %s not found", paymentAccountId));

        String upperCaseCurrencyCode = currencyCode.toUpperCase();
        String offerId = getRandomOfferId();
        OfferDirection direction = OfferDirection.valueOf(directionAsString.toUpperCase());
        Price price = Price.valueOf(upperCaseCurrencyCode, priceStringToLong(priceAsString, upperCaseCurrencyCode));
        Coin amount = Coin.valueOf(amountAsLong);
        Coin minAmount = minAmountAsLong == 0 ? amount : Coin.valueOf(minAmountAsLong);
        Coin useDefaultTxFee = Coin.ZERO;

        // Almost ready to call createOfferService.createAndGetOffer(), but first:
        //
        // For the buyer security deposit parameter, API clients pass a double as a
        // percent literal, e.g., #.## (%), where "1.00 means 1% of the trade amount".
        // Desktop (UI) clients convert the percent literal string input before passing
        // a representation of a pct as a decimal, e.g., 0.##.
        // See bisq.desktop.main.offer.bisq_v1.MutableOfferDataModel, where
        // "Pct value of buyer security deposit, e.g., 0.01 means 1% of trade amount."
        //
        // The API client's percent literal is transformed now, to make sure the double
        // passed into createOfferService.createAndGetOffer() is correctly scaled.
        double scaledBuyerSecurityDepositPct = exactMultiply(buyerSecurityDepositPct, 0.01);

        Offer offer = createOfferService.createAndGetOffer(offerId,
                direction,
                upperCaseCurrencyCode,
                amount,
                minAmount,
                price,
                useDefaultTxFee,
                useMarketBasedPrice,
                exactMultiply(marketPriceMargin, 0.01),
                scaledBuyerSecurityDepositPct,
                paymentAccount);

        verifyPaymentAccountIsValidForNewOffer(offer, paymentAccount);

        // We don't support atm funding from external wallet to keep it simple.
        boolean useSavingsWallet = true;
        placeOffer(offer,
                scaledBuyerSecurityDepositPct,
                triggerPrice,
                useSavingsWallet,
                transaction -> resultHandler.accept(offer));
    }

    // Edit a placed offer.
    void editOffer(String offerId,
                   String editedPrice,
                   boolean editedUseMarketBasedPrice,
                   double editedMarketPriceMargin,
                   String editedTriggerPrice,
                   int editedEnable,
                   EditType editType) {
        OpenOffer openOffer = getMyOpenOffer(offerId);
        var validator = new EditOfferValidator(openOffer,
                editedPrice,
                editedUseMarketBasedPrice,
                editedMarketPriceMargin,
                editedTriggerPrice,
                editedEnable,
                editType).validate();
        log.info(validator.toString());
        OpenOffer.State currentOfferState = openOffer.getState();
        // Client sent (sint32) editedEnable, not a bool (with default=false).
        // If editedEnable = -1, do not change current state
        // If editedEnable =  0, set state = AVAILABLE
        // If editedEnable =  1, set state = DEACTIVATED
        OpenOffer.State newOfferState = editedEnable < 0
                ? currentOfferState
                : editedEnable > 0 ? AVAILABLE : DEACTIVATED;
        OfferPayload editedPayload = getMergedOfferPayload(validator,
                openOffer,
                editedPrice,
                editedMarketPriceMargin,
                editType);
        Offer editedOffer = new Offer(editedPayload);
        priceFeedService.setCurrencyCode(openOffer.getOffer().getCurrencyCode());
        editedOffer.setPriceFeedService(priceFeedService);
        editedOffer.setState(State.AVAILABLE);
        openOfferManager.editOpenOfferStart(openOffer,
                () -> log.info("EditOpenOfferStart: offer {}", openOffer.getId()),
                log::error);
        long triggerPriceAsLong = getMarketPriceAsLong(editedTriggerPrice, editedOffer.getCurrencyCode());
        openOfferManager.editOpenOfferPublish(editedOffer,
                triggerPriceAsLong,
                newOfferState,
                () -> log.info("EditOpenOfferPublish: offer {}", openOffer.getId()),
                log::error);
    }

    void cancelOffer(String id) {
        OpenOffer openOffer = getMyOffer(id);
        openOfferManager.removeOffer(openOffer.getOffer(),
                () -> {
                },
                log::error);
    }

    private void placeBsqSwapOffer(Offer offer, Runnable resultHandler) {
        openBsqSwapOfferService.placeBsqSwapOffer(offer,
                resultHandler,
                log::error);

        if (offer.getErrorMessage() != null)
            throw new IllegalStateException(offer.getErrorMessage());
    }

    private void placeOffer(Offer offer,
                            double buyerSecurityDepositPct,
                            String triggerPrice,
                            boolean useSavingsWallet,
                            Consumer<Transaction> resultHandler) {
        var triggerPriceAsLong = getMarketPriceAsLong(triggerPrice, offer.getCurrencyCode());
        openOfferManager.placeOffer(offer,
                buyerSecurityDepositPct,
                useSavingsWallet,
                false,
                triggerPriceAsLong,
                resultHandler::accept,
                log::error);

        if (offer.getErrorMessage() != null)
            throw new IllegalStateException(offer.getErrorMessage());
    }

    private OfferPayload getMergedOfferPayload(EditOfferValidator editOfferValidator,
                                               OpenOffer openOffer,
                                               String editedPriceAsString,
                                               double editedMarketPriceMargin,
                                               EditType editType) {
        // API supports editing (1) price, OR (2) marketPriceMargin & useMarketBasedPrice
        // OfferPayload fields.  API does not support editing payment acct or currency
        // code fields.  Note: triggerPrice isDeactivated fields are in OpenOffer, not
        // in OfferPayload.
        Offer offer = openOffer.getOffer();
        String currencyCode = offer.getCurrencyCode();
        boolean isUsingMktPriceMargin = editOfferValidator.isEditingUseMktPriceMarginFlag.test(offer, editType);
        boolean isEditingFixedPrice = editType.equals(FIXED_PRICE_ONLY) || editType.equals(FIXED_PRICE_AND_ACTIVATION_STATE);
        Price editedFixedPrice;
        if (isEditingFixedPrice) {
            editedFixedPrice = Price.valueOf(currencyCode, priceStringToLong(editedPriceAsString, currencyCode));
        } else {
            // When isUsingMktPriceMargin=true, (fixed) price must be set to 0 on the server.
            // The client, however, still must show the calculated price when
            // isUsingMktPriceMargin=true.
            editedFixedPrice = isUsingMktPriceMargin ? Price.valueOf(currencyCode, 0) : offer.getPrice();
        }

        // If isUsingMktPriceMargin=true, throw exception if new fixed-price != 0.
        // If isUsingMktPriceMargin=false, throw exception if new fixed-price == 0.
        if (isUsingMktPriceMargin && editedFixedPrice.getValue() != 0)
            throw new IllegalStateException(
                    format("Fixed price on mkt price margin based offer %s must be set to 0 in server.",
                            offer.getId()));
        else if (!isUsingMktPriceMargin && requireNonNull(editedFixedPrice).getValue() == 0)
            throw new IllegalStateException(
                    format("Fixed price on fixed price offer %s cannot be 0.", offer.getId()));

        boolean isEditingMktPriceMargin = editOfferValidator.isEditingMktPriceMargin.test(editType);
        double newMarketPriceMargin = isEditingMktPriceMargin
                ? exactMultiply(editedMarketPriceMargin, 0.01)
                : offer.getMarketPriceMargin();

        MutableOfferPayloadFields mutableOfferPayloadFields = new MutableOfferPayloadFields(
                requireNonNull(editedFixedPrice).getValue(),
                isUsingMktPriceMargin ? newMarketPriceMargin : 0.00,
                isUsingMktPriceMargin,
                offer.getBaseCurrencyCode(),
                offer.getCounterCurrencyCode(),
                offer.getPaymentMethod().getId(),
                offer.getMakerPaymentAccountId(),
                offer.getMaxTradeLimit().getValue(),
                offer.getMaxTradePeriod(),
                offer.getCountryCode(),
                offer.getAcceptedCountryCodes(),
                offer.getBankId(),
                offer.getAcceptedBankIds(),
                offer.getExtraDataMap());
        log.info("Merging OfferPayload with {}", mutableOfferPayloadFields);
        return offerUtil.getMergedOfferPayload(openOffer, mutableOfferPayloadFields);
    }

    private void verifyPaymentAccountIsValidForNewOffer(Offer offer, PaymentAccount paymentAccount) {
        if (!isPaymentAccountValidForOffer(offer, paymentAccount)) {
            String error = format("cannot create %s offer with payment account %s",
                    offer.getCounterCurrencyCode(),
                    paymentAccount.getId());
            throw new IllegalStateException(error);
        }
    }

    private boolean offerMatchesDirectionAndCurrency(Offer offer,
                                                     String direction,
                                                     String currencyCode) {
        var isDirectionMatch = offer.getDirection().name().equalsIgnoreCase(direction);
        var isCurrencyMatch = offer.getCounterCurrencyCode().equalsIgnoreCase(currencyCode);
        return isDirectionMatch && isCurrencyMatch;
    }

    private Comparator<OpenOffer> openOfferPriceComparator(String direction, boolean isFiat) {
        // A buyer probably wants to see sell orders in price ascending order.
        // A seller probably wants to see buy orders in price descending order.
        if (isFiat)
            return direction.equalsIgnoreCase(BUY.name())
                    ? openOfferPriceComparator.get().reversed()
                    : openOfferPriceComparator.get();
        else
            return direction.equalsIgnoreCase(SELL.name())
                    ? openOfferPriceComparator.get().reversed()
                    : openOfferPriceComparator.get();
    }

    private Comparator<Offer> priceComparator(String direction, boolean isFiat) {
        // A buyer probably wants to see sell orders in price ascending order.
        // A seller probably wants to see buy orders in price descending order.
        if (isFiat)
            return direction.equalsIgnoreCase(BUY.name())
                    ? priceComparator.get().reversed()
                    : priceComparator.get();
        else
            return direction.equalsIgnoreCase(SELL.name())
                    ? priceComparator.get().reversed()
                    : priceComparator.get();
    }


    private long priceStringToLong(String priceAsString, String currencyCode) {
        int precision = isCryptoCurrency(currencyCode) ? Altcoin.SMALLEST_UNIT_EXPONENT : Fiat.SMALLEST_UNIT_EXPONENT;
        double priceAsDouble = new BigDecimal(priceAsString).doubleValue();
        double scaled = scaleUpByPowerOf10(priceAsDouble, precision);
        return roundDoubleToLong(scaled);
    }
}
