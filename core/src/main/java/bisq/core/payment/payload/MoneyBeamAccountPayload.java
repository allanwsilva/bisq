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

package bisq.core.payment.payload;

import bisq.core.locale.Res;

import com.google.protobuf.Message;

import org.apache.commons.lang3.ArrayUtils;

import java.nio.charset.StandardCharsets;

import java.util.HashMap;
import java.util.Map;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(callSuper = true)
@ToString
@Setter
@Getter
@Slf4j
public final class MoneyBeamAccountPayload extends PaymentAccountPayload {
    private String accountId = "";

    public MoneyBeamAccountPayload(String paymentMethod, String id) {
        super(paymentMethod, id);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private MoneyBeamAccountPayload(String paymentMethod,
                                    String id,
                                    String accountId,
                                    long maxTradePeriod,
                                    Map<String, String> excludeFromJsonDataMap) {
        super(paymentMethod,
                id,
                maxTradePeriod,
                excludeFromJsonDataMap);

        this.accountId = accountId;
    }

    @Override
    public Message toProtoMessage() {
        return getPaymentAccountPayloadBuilder()
                .setMoneyBeamAccountPayload(protobuf.MoneyBeamAccountPayload.newBuilder()
                        .setAccountId(accountId))
                .build();
    }

    public static MoneyBeamAccountPayload fromProto(protobuf.PaymentAccountPayload proto) {
        return new MoneyBeamAccountPayload(proto.getPaymentMethodId(),
                proto.getId(),
                proto.getMoneyBeamAccountPayload().getAccountId(),
                proto.getMaxTradePeriod(),
                new HashMap<>(proto.getExcludeFromJsonDataMap()));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public String getPaymentDetails() {
        return Res.get(paymentMethodId) + " - " + getPaymentDetailsForTradePopup().replace("\n", ", ");
    }

    @Override
    public String getPaymentDetailsForTradePopup() {
        return Res.getWithCol("payment.account") + " " + accountId + "\n" +
                Res.getWithCol("payment.account.owner.fullname") + " " + getHolderNameOrPromptIfEmpty();
    }

    @Override
    public byte[] getAgeWitnessInputData() {
        // holderName will be included as part of the witness data.
        // older accounts that don't have holderName still retain their existing witness.
        return super.getAgeWitnessInputData(ArrayUtils.addAll(
                accountId.getBytes(StandardCharsets.UTF_8),
                getHolderName().getBytes(StandardCharsets.UTF_8)));
    }
}
