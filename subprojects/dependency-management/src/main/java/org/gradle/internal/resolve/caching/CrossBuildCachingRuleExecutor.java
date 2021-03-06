/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.resolve.caching;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.gradle.api.Action;
import org.gradle.api.Transformer;
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy;
import org.gradle.api.internal.changedetection.state.InMemoryCacheDecoratorFactory;
import org.gradle.api.internal.changedetection.state.SnapshotSerializer;
import org.gradle.api.internal.changedetection.state.ValueSnapshot;
import org.gradle.api.internal.changedetection.state.ValueSnapshotter;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.cache.PersistentIndexedCacheParameters;
import org.gradle.cache.internal.filelock.LockOptionsBuilder;
import org.gradle.internal.Cast;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.reflect.ConfigurableRule;
import org.gradle.internal.reflect.InstantiatingAction;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.BaseSerializerFactory;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;
import org.gradle.util.BuildCommencedTimeProvider;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class CrossBuildCachingRuleExecutor<KEY, DETAILS, RESULT> implements CachingRuleExecutor<KEY, DETAILS, RESULT>, Closeable {
    private final static Logger LOGGER = Logging.getLogger(CrossBuildCachingRuleExecutor.class);

    private final ValueSnapshotter snapshotter;
    private final Transformer<Serializable, KEY> ketToSnapshottable;
    private final PersistentCache cache;
    private final PersistentIndexedCache<ValueSnapshot, CachedEntry<RESULT>> store;
    private final BuildCommencedTimeProvider timeProvider;
    private final EntryValidator<RESULT> validator;

    public CrossBuildCachingRuleExecutor(String name,
                                         CacheRepository cacheRepository,
                                         InMemoryCacheDecoratorFactory cacheDecoratorFactory,
                                         ValueSnapshotter snapshotter,
                                         BuildCommencedTimeProvider timeProvider,
                                         EntryValidator<RESULT> validator,
                                         Transformer<Serializable, KEY> ketToSnapshottable,
                                         Serializer<RESULT> resultSerializer) {
        this.snapshotter = snapshotter;
        this.validator = validator;
        this.ketToSnapshottable = ketToSnapshottable;
        this.timeProvider = timeProvider;
        this.cache = cacheRepository
            .cache(name)
            .withLockOptions(LockOptionsBuilder.mode(FileLockManager.LockMode.None))
            .open();
        PersistentIndexedCacheParameters<ValueSnapshot, CachedEntry<RESULT>> cacheParams = createCacheConfiguration(name, resultSerializer, cacheDecoratorFactory);
        this.store = this.cache.createCache(cacheParams);
    }

    private PersistentIndexedCacheParameters<ValueSnapshot, CachedEntry<RESULT>> createCacheConfiguration(String name, Serializer<RESULT> resultSerializer, InMemoryCacheDecoratorFactory cacheDecoratorFactory) {
        Serializer<ValueSnapshot> snapshotSerializer = new SnapshotSerializer();

        PersistentIndexedCacheParameters<ValueSnapshot, CachedEntry<RESULT>> cacheParams = new PersistentIndexedCacheParameters<ValueSnapshot, CachedEntry<RESULT>>(
            name,
            snapshotSerializer,
            createEntrySerializer(resultSerializer)
        );
        cacheParams.cacheDecorator(cacheDecoratorFactory.decorator(2000, true));
        return cacheParams;
    }

    private Serializer<CachedEntry<RESULT>> createEntrySerializer(final Serializer<RESULT> resultSerializer) {
        return new CacheEntrySerializer<RESULT>(resultSerializer);
    }

    @Override
    public <D extends DETAILS> RESULT execute(final KEY key, final InstantiatingAction<DETAILS> action, final Transformer<RESULT, D> detailsToResult, final Transformer<D, KEY> onCacheMiss, final CachePolicy cachePolicy) {
        if (action == null) {
            return null;
        }
        final ConfigurableRule<DETAILS> rule = action.getRule();
        if (rule.isCacheable()) {
            return tryFromCache(key, action, detailsToResult, onCacheMiss, cachePolicy, rule);
        } else {
            return executeRule(key, action, detailsToResult, onCacheMiss);
        }
    }

    private <D extends DETAILS> RESULT tryFromCache(KEY key, InstantiatingAction<DETAILS> action, Transformer<RESULT, D> detailsToResult, Transformer<D, KEY> onCacheMiss, CachePolicy cachePolicy, ConfigurableRule<DETAILS> rule) {
        final ValueSnapshot snapshot = computeExplicitInputsSnapshot(key, rule);
        DefaultImplicitInputRegistrar registrar = new DefaultImplicitInputRegistrar();
        ImplicitInputsCapturingInstantiator instantiator = findInputCapturingInstantiator(action);
        if (instantiator != null) {
            action = action.withInstantiator(instantiator.capturing(registrar));
        }
        // First step is to find an entry with the explicit inputs in the cache
        CachedEntry<RESULT> entry = store.get(snapshot);
        if (entry != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Found result for rule {} and key {} in cache", rule, key);
            }
            if (validator.isValid(cachePolicy, entry) && areImplicitInputsUpToDate(instantiator, key, rule, entry)) {
                // Here it means that we have validated that the entry is still up-to-date, and that means a couple of things:
                // 1. the cache policy said that the entry is still valid (for example, `--refresh-dependencies` wasn't called)
                // 2. if the rule is cacheable, we have validated that its discovered inputs are still the same
                return entry.getResult();
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Invalidating result for rule {} and key {} in cache", rule, key);
            }
        }

        RESULT result = executeRule(key, action, detailsToResult, onCacheMiss);
        if (result != null) {
            store.put(snapshot, new CachedEntry<RESULT>(timeProvider.getCurrentTime(), registrar.implicits, result));
        }
        return result;
    }

    /**
     * This method computes a snapshot of the explicit inputs of the rule, which consist of the rule implementation,
     * the rule key (for example, a module identifier) and the optional rule parameters.
     * @param key the primary key
     * @param rule the rule to be snapshotted
     * @return a snapshot of the inputs
     */
    private ValueSnapshot computeExplicitInputsSnapshot(KEY key, ConfigurableRule<DETAILS> rule) {
        List<Object> toBeSnapshotted = Lists.newArrayListWithExpectedSize(4);
        toBeSnapshotted.add(ketToSnapshottable.transform(key));
        Class<? extends Action<DETAILS>> ruleClass = rule.getRuleClass();
        Object[] ruleParams = rule.getRuleParams();
        toBeSnapshotted.add(ruleClass);
        toBeSnapshotted.add(ruleParams);
        return snapshotter.snapshot(toBeSnapshotted);
    }

    private ImplicitInputsCapturingInstantiator findInputCapturingInstantiator(InstantiatingAction<DETAILS> action) {
        Instantiator instantiator = action.getInstantiator();
        if (instantiator instanceof ImplicitInputsCapturingInstantiator) {
            return (ImplicitInputsCapturingInstantiator) instantiator;
        }
        return null;
    }

    private boolean areImplicitInputsUpToDate(ImplicitInputsCapturingInstantiator serviceRegistry, KEY key, ConfigurableRule<DETAILS> rule, CachedEntry<RESULT> entry) {
        for (Map.Entry<String, Collection<ImplicitInputRecord<?, ?>>> implicitEntry : entry.getImplicits().asMap().entrySet()) {
            String serviceName = implicitEntry.getKey();
            ImplicitInputsProvidingService<Object, Object, ?> provider = Cast.uncheckedCast(serviceRegistry.findInputCapturingServiceByName(serviceName));
            for (ImplicitInputRecord<?, ?> list : implicitEntry.getValue()) {
                if (!provider.isUpToDate(list.getInput(), list.getOutput())) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Invalidating result for rule {} and key {} in cache because implicit input provided by service {} changed", rule, key, provider.getClass());
                    }
                    return false;
                }
            }
        }
        return true;
    }

    private <D extends DETAILS> RESULT executeRule(KEY key, InstantiatingAction<DETAILS> action, Transformer<RESULT, D> detailsToResult, Transformer<D, KEY> onCacheMiss) {
        D details = onCacheMiss.transform(key);
        action.execute(details);
        return detailsToResult.transform(details);
    }

    @Override
    public void close() throws IOException {
        cache.close();
    }

    public static class CachedEntry<RESULT> {
        private final long timestamp;
        private final Multimap<String, ImplicitInputRecord<?, ?>> implicits;
        private final RESULT result;

        private CachedEntry(long timestamp, Multimap<String, ImplicitInputRecord<?, ?>> implicits, RESULT result) {
            this.timestamp = timestamp;
            this.implicits = implicits;
            this.result = result;
        }

        public Multimap<String, ImplicitInputRecord<?, ?>> getImplicits() {
            return implicits;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public RESULT getResult() {
            return result;
        }
    }

    /**
     * When getting a result from the cache, we need to check whether the
     * result is still valid or not. We cannot take that decision before
     * knowing the actual type of KEY, so we need to provide this as a
     * pluggable strategy when creating the executor.
     *
     * @param <RESULT> the type of entry stored in the cache.
     */
    public interface EntryValidator<RESULT> {
        boolean isValid(CachePolicy policy, CachedEntry<RESULT> entry);
    }

    private static class CacheEntrySerializer<RESULT> extends AbstractSerializer<CachedEntry<RESULT>> {
        private final Serializer<RESULT> resultSerializer;
        private final AnySerializer anySerializer = new AnySerializer();

        public CacheEntrySerializer(Serializer<RESULT> resultSerializer) {
            this.resultSerializer = resultSerializer;
        }

        @Override
        public CachedEntry<RESULT> read(Decoder decoder) throws Exception {
            return new CachedEntry<RESULT>(decoder.readLong(), readImplicits(decoder), resultSerializer.read(decoder));
        }

        private Multimap<String, ImplicitInputRecord<?, ?>> readImplicits(Decoder decoder) throws Exception {
            int cpt = decoder.readSmallInt();
            Multimap<String, ImplicitInputRecord<?, ?>> result = HashMultimap.create();
            for (int i = 0; i < cpt; i++) {
                String impl = decoder.readString();
                List<ImplicitInputRecord<?, ?>> implicitInputOutputs = readImplicitList(decoder);
                result.putAll(impl, implicitInputOutputs);
            }
            return result;
        }

        List<ImplicitInputRecord<?, ?>> readImplicitList(Decoder decoder) throws Exception {
            int cpt = decoder.readSmallInt();
            List<ImplicitInputRecord<?, ?>> implicits = Lists.newArrayListWithCapacity(cpt);
            for (int i = 0; i < cpt; i++) {
                final Object in = readAny(decoder);
                final Object out = readAny(decoder);
                implicits.add(new ImplicitInputRecord<Object, Object>() {
                    @Override
                    public Object getInput() {
                        return in;
                    }

                    @Nullable
                    @Override
                    public Object getOutput() {
                        return out;
                    }
                });
            }
            return implicits;
        }

        @Nullable
        private Object readAny(Decoder decoder) throws Exception {
            return anySerializer.read(decoder);
        }

        @Override
        public void write(Encoder encoder, CachedEntry<RESULT> value) throws Exception {
            encoder.writeLong(value.timestamp);
            writeImplicits(encoder, value.implicits);
            resultSerializer.write(encoder, value.result);
        }

        private void writeImplicits(Encoder encoder, Multimap<String, ImplicitInputRecord<?, ?>> implicits) throws Exception {
            encoder.writeSmallInt(implicits.size());
            for (Map.Entry<String, Collection<ImplicitInputRecord<?, ?>>> entry : implicits.asMap().entrySet()) {
                encoder.writeString(entry.getKey());
                writeImplicitList(encoder, entry.getValue());
            }
        }

        private void writeImplicitList(Encoder encoder, Collection<ImplicitInputRecord<?, ?>> implicits) throws Exception {
            encoder.writeSmallInt(implicits.size());
            for (ImplicitInputRecord<?, ?> implicit : implicits) {
                writeAny(encoder, implicit.getInput());
                writeAny(encoder, implicit.getOutput());
            }
        }

        private void writeAny(Encoder encoder, Object any) throws Exception {
            anySerializer.write(encoder, any);
        }
    }

    private static class DefaultImplicitInputRegistrar implements ImplicitInputRecorder {
        final Multimap<String, ImplicitInputRecord<?, ?>> implicits = HashMultimap.create();

        @Override
        public <IN, OUT> void register(String serviceName, ImplicitInputRecord<IN, OUT> input) {
            implicits.put(serviceName, input);
        }
    }

    private static class AnySerializer implements Serializer<Object> {
        private static final BaseSerializerFactory SERIALIZER_FACTORY = new BaseSerializerFactory();

        private static final Class<?>[] USUAL_TYPES = new Class[] {
            String.class,
            Boolean.class,
            Long.class,
            File.class,
            byte[].class,
            HashCode.class,
            Throwable.class
        };

        @Override
        public Object read(Decoder decoder) throws Exception {
            int index = decoder.readSmallInt();
            if (index == -1) {
                return null;
            }
            Class<?> clazz;
            if (index == -2) {
                String typeName = decoder.readString();
                clazz = Class.forName(typeName);
            } else {
                clazz = USUAL_TYPES[index];
            }

            return SERIALIZER_FACTORY.getSerializerFor(clazz).read(decoder);
        }

        @Override
        public void write(Encoder encoder, Object value) throws Exception {
            if (value == null) {
                encoder.writeSmallInt(-1);
                return;
            }
            Class<?> anyType = value.getClass();
            Serializer<Object> serializer = Cast.uncheckedCast(SERIALIZER_FACTORY.getSerializerFor(anyType));
            for (int i = 0; i < USUAL_TYPES.length; i++) {
                if (USUAL_TYPES[i].equals(anyType)) {
                    encoder.writeSmallInt(i);
                    serializer.write(encoder, value);
                    return;
                }
            }
            encoder.writeSmallInt(-2);
            encoder.writeString(anyType.getName());
            serializer.write(encoder, value);
        }
    }
}
