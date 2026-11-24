/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.MessageLite;
import com.google.protobuf.util.JsonFormat;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author Gianlu
 */
public final class ProtobufToJson {

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
            .omittingInsignificantWhitespace()
            .includingDefaultValueFields();

    private ProtobufToJson() {
    }

    @NotNull
    public static JsonObject convert(@NotNull com.google.protobuf.MessageLite message) {
        try {
            // JsonFormat requires the full Message interface; cast is safe because
            // lite generated classes implement Message when using the full printer.
            String json = PRINTER.print((com.google.protobuf.Message) message);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("ProtobufToJson.convert failed", e);
        }
    }

    @NotNull
    public static JsonArray convertList(@NotNull List<? extends com.google.protobuf.MessageLite> list) {
        JsonArray array = new JsonArray(list.size());
        for (com.google.protobuf.MessageLite msg : list) array.add(convert(msg));
        return array;
    }
}
