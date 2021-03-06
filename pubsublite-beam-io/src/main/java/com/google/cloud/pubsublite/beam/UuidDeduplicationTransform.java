/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsublite.beam;

import com.google.cloud.pubsublite.SequencedMessage;
import java.math.BigInteger;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.ProcessFunction;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;

class UuidDeduplicationTransform
    extends PTransform<PCollection<SequencedMessage>, PCollection<SequencedMessage>> {
  private final UuidDeduplicationOptions options;

  UuidDeduplicationTransform(UuidDeduplicationOptions options) {
    this.options = options;
  }

  @Override
  public PCollection<SequencedMessage> expand(PCollection<SequencedMessage> input) {
    input.getPipeline().getCoderRegistry().registerCoderForClass(Uuid.class, Uuid.getCoder());
    input
        .getPipeline()
        .getCoderRegistry()
        .registerCoderForClass(SequencedMessage.class, new SequencedMessageCoder());
    ProcessFunction<SequencedMessage, KV<Uuid, SequencedMessage>> mapWithKeys =
        message -> KV.of(options.uuidExtractor().apply(message), message);
    PCollection<KV<Uuid, SequencedMessage>> uuidMapped =
        input.apply(
            "MapUuids",
            MapElements.into(new TypeDescriptor<KV<Uuid, SequencedMessage>>() {}).via(mapWithKeys));
    ProcessFunction<KV<Uuid, SequencedMessage>, KV<Integer, KV<Uuid, SequencedMessage>>>
        mapWithHash =
            kv ->
                KV.of(
                    new BigInteger(/*signum=*/ 1, kv.getKey().value().toByteArray())
                        .mod(BigInteger.valueOf(options.hashPartitions()))
                        .intValue(),
                    kv);
    PCollection<KV<Integer, KV<Uuid, SequencedMessage>>> hashedUuids =
        uuidMapped.apply(
            "HashUuids",
            MapElements.into(new TypeDescriptor<KV<Integer, KV<Uuid, SequencedMessage>>>() {})
                .via(mapWithHash));
    PCollection<KV<Uuid, SequencedMessage>> unique =
        hashedUuids.apply(
            "Deduplicate", ParDo.of(new DeduplicationFn<>(options.deduplicationFnOptions())));
    return unique.apply("StripUuids", Values.create());
  }
}
