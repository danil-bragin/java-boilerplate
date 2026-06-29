package com.acme.bank.contracts;

import com.acme.money.Assets;
import com.acme.money.Money;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * Teaches Jackson how to round-trip {@link Money} for the Modulith outbox (event_publication). The
 * wire format is {@code {"amount":"100","asset":"USD"}} — the same string representation used by the
 * Avro contract ({@link MoneyMapper}), so JSON and Avro stay byte-for-byte consistent on amount/asset.
 *
 * <p>Lives in {@code bank-contracts} (the boundary-crossing module that already owns {@link MoneyMapper})
 * so every bank service inherits one canonical Money serde via {@code MoneyJacksonAutoConfiguration}
 * instead of redefining it. Spring Boot auto-registers any {@code com.fasterxml.jackson.databind.Module}
 * bean into the application {@code ObjectMapper}.
 */
public final class MoneyJacksonModule extends SimpleModule {

    public MoneyJacksonModule() {
        super("MoneyModule");
        addSerializer(Money.class, new MoneySerializer());
        addDeserializer(Money.class, new MoneyDeserializer());
    }

    private static final class MoneySerializer extends JsonSerializer<Money> {
        @Override
        public void serialize(Money money, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("amount", money.toAmountString());
            gen.writeStringField("asset", money.asset().code());
            gen.writeEndObject();
        }
    }

    private static final class MoneyDeserializer extends JsonDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            ObjectNode node = p.readValueAsTree();
            String amount = node.get("amount").asText();
            String asset = node.get("asset").asText();
            return Money.of(amount, Assets.of(asset));
        }
    }
}
