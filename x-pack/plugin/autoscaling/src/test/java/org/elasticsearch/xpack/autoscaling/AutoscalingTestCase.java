/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.autoscaling;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.autoscaling.capacity.AutoscalingCapacity;
import org.elasticsearch.xpack.autoscaling.capacity.FixedAutoscalingDeciderConfiguration;
import org.elasticsearch.xpack.autoscaling.capacity.AutoscalingDeciderConfiguration;
import org.elasticsearch.xpack.autoscaling.capacity.AutoscalingDeciderResult;
import org.elasticsearch.xpack.autoscaling.capacity.AutoscalingDeciderResults;
import org.elasticsearch.xpack.autoscaling.capacity.FixedAutoscalingDeciderService;
import org.elasticsearch.xpack.autoscaling.policy.AutoscalingPolicy;
import org.elasticsearch.xpack.autoscaling.policy.AutoscalingPolicyMetadata;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class AutoscalingTestCase extends ESTestCase {

    public static AutoscalingDeciderResult randomAutoscalingDeciderResult() {
        AutoscalingCapacity capacity = randomNullableAutoscalingCapacity();
        return randomAutoscalingDeciderResultWithCapacity(capacity);
    }

    protected static AutoscalingDeciderResult randomAutoscalingDeciderResultWithCapacity(AutoscalingCapacity capacity) {
        return new AutoscalingDeciderResult(
            capacity,
            new FixedAutoscalingDeciderService.FixedReason(randomNullableByteSizeValue(), randomNullableByteSizeValue(), randomInt(1000))
        );
    }

    public static AutoscalingDeciderResults randomAutoscalingDeciderResults() {
        final SortedMap<String, AutoscalingDeciderResult> results = IntStream.range(0, randomIntBetween(1, 10))
            .mapToObj(i -> Tuple.tuple(Integer.toString(i), randomAutoscalingDeciderResult()))
            .collect(Collectors.toMap(Tuple::v1, Tuple::v2, (a, b) -> { throw new IllegalStateException(); }, TreeMap::new));
        AutoscalingCapacity capacity = new AutoscalingCapacity(randomAutoscalingResources(), randomAutoscalingResources());
        return new AutoscalingDeciderResults(capacity, results);
    }

    public static AutoscalingCapacity randomAutoscalingCapacity() {
        AutoscalingCapacity.AutoscalingResources tier = randomNullValueAutoscalingResources();
        return new AutoscalingCapacity(
            tier,
            randomBoolean() ? randomNullValueAutoscalingResources(tier.storage() != null, tier.memory() != null) : null
        );
    }

    protected static AutoscalingCapacity randomNullableAutoscalingCapacity() {
        return randomBoolean() ? randomAutoscalingCapacity() : null;
    }

    protected static AutoscalingCapacity.AutoscalingResources randomAutoscalingResources() {
        return new AutoscalingCapacity.AutoscalingResources(randomByteSizeValue(), randomByteSizeValue());
    }

    private static AutoscalingCapacity.AutoscalingResources randomNullValueAutoscalingResources() {
        return randomNullValueAutoscalingResources(true, true);
    }

    public static AutoscalingCapacity.AutoscalingResources randomNullValueAutoscalingResources(boolean allowStorage, boolean allowMemory) {
        assert allowMemory || allowStorage;
        boolean addStorage = (allowStorage && randomBoolean()) || allowMemory == false;
        boolean addMemory = (allowMemory && randomBoolean()) || addStorage == false;
        return new AutoscalingCapacity.AutoscalingResources(
            addStorage ? randomByteSizeValue() : null,
            addMemory ? randomByteSizeValue() : null
        );
    }

    public static ByteSizeValue randomByteSizeValue() {
        // do not want to test any overflow.
        return new ByteSizeValue(randomLongBetween(0, Long.MAX_VALUE >> 16));
    }

    public static ByteSizeValue randomNullableByteSizeValue() {
        return randomBoolean() ? randomByteSizeValue() : null;
    }

    public static SortedMap<String, AutoscalingDeciderConfiguration> randomAutoscalingDeciders() {
        return new TreeMap<>(
            List.of(randomFixedDecider()).stream().collect(Collectors.toMap(AutoscalingDeciderConfiguration::name, Function.identity()))
        );
    }

    public static FixedAutoscalingDeciderConfiguration randomFixedDecider() {
        return new FixedAutoscalingDeciderConfiguration(
            randomNullableByteSizeValue(),
            randomNullableByteSizeValue(),
            randomFrom(randomInt(1000), null)
        );
    }

    public static AutoscalingPolicy randomAutoscalingPolicy() {
        return randomAutoscalingPolicyOfName(randomAlphaOfLength(8));
    }

    public static AutoscalingPolicy randomAutoscalingPolicyOfName(final String name) {
        return new AutoscalingPolicy(name, randomAutoscalingDeciders());
    }

    public static AutoscalingPolicy mutateAutoscalingPolicy(final AutoscalingPolicy instance) {
        final SortedMap<String, AutoscalingDeciderConfiguration> deciders;
        if (randomBoolean()) {
            // if the policy name did not change, or randomly, use a mutated set of deciders
            deciders = mutateAutoscalingDeciders(instance.deciders());
        } else {
            deciders = instance.deciders();
        }
        return new AutoscalingPolicy(randomValueOtherThan(instance.name(), () -> randomAlphaOfLength(8)), deciders);
    }

    public static SortedMap<String, AutoscalingDeciderConfiguration> mutateAutoscalingDeciders(
        final SortedMap<String, AutoscalingDeciderConfiguration> deciders
    ) {
        if (deciders.size() == 0) {
            return randomAutoscalingDeciders();
        } else {
            // use a proper subset of the deciders
            return new TreeMap<>(
                randomSubsetOf(randomIntBetween(0, deciders.size() - 1), deciders.entrySet()).stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            );
        }
    }

    public static AutoscalingMetadata randomAutoscalingMetadata() {
        return randomAutoscalingMetadataOfPolicyCount(randomIntBetween(0, 8));
    }

    public static AutoscalingMetadata randomAutoscalingMetadataOfPolicyCount(final int numberOfPolicies) {
        final SortedMap<String, AutoscalingPolicyMetadata> policies = new TreeMap<>();
        for (int i = 0; i < numberOfPolicies; i++) {
            final AutoscalingPolicy policy = randomAutoscalingPolicy();
            final AutoscalingPolicyMetadata policyMetadata = new AutoscalingPolicyMetadata(policy);
            policies.put(policy.name(), policyMetadata);
        }
        return new AutoscalingMetadata(policies);
    }

    public static NamedWriteableRegistry getAutoscalingNamedWriteableRegistry() {
        return new NamedWriteableRegistry(new Autoscaling(Settings.EMPTY).getNamedWriteables());
    }

    public static NamedXContentRegistry getAutoscalingXContentRegistry() {
        return new NamedXContentRegistry(new Autoscaling(Settings.EMPTY).getNamedXContent());
    }
}
