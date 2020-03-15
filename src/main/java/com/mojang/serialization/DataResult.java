// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.serialization;

import com.mojang.datafixers.kinds.App;
import com.mojang.datafixers.kinds.Applicative;
import com.mojang.datafixers.kinds.K1;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Function3;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class DataResult<R> implements App<DataResult.Mu, R> {
    public static final class Mu implements K1 {}

    public static <R> DataResult<R> unbox(final App<DataResult.Mu, R> box) {
        return (DataResult<R>) box;
    }

    private final Either<R, DynamicException<R>> result;

    public static <R> DataResult<R> success(final R result) {
        return new DataResult<>(Either.left(result));
    }

    public static <R> DataResult<R> error(final String message, final R partialResult) {
        return new DataResult<>(Either.right(new DynamicException<>(message, Optional.of(partialResult))));
    }

    public static <R> DataResult<R> error(final String message) {
        return new DataResult<>(Either.right(new DynamicException<>(message, Optional.empty())));
    }

    public static <R> DataResult<R> create(final Either<R, DynamicException<R>> result) {
        return new DataResult<>(result);
    }

    private DataResult(final Either<R, DynamicException<R>> result) {
        this.result = result;
    }

    public Either<R, DynamicException<R>> get() {
        return result;
    }

    public Optional<R> result() {
        return result.left();
    }

    public Optional<R> resultOrPartial(final Consumer<String> onError) {
        return result.map(
            Optional::of,
            r -> {
                onError.accept(r.message);
                return r.partialResult;
            }
        );
    }

    public R getOrThrow(final boolean allowPartial, final Consumer<String> onError) {
        return result.map(
            l -> l,
            r -> {
                onError.accept(r.message);
                if (allowPartial && r.partialResult.isPresent()) {
                    return r.partialResult.get();
                }
                throw new RuntimeException(r.message);
            }
        );
    }

    public Optional<DynamicException<R>> error() {
        return result.right();
    }

    public <T> DataResult<T> map(final Function<? super R, ? extends T> function) {
        return create(result.mapBoth(
            function,
            r -> new DynamicException<>(r.message, r.partialResult.map(function))
        ));
    }

    /**
     * Applies the function to either full or partial result, in case of partial concatenates errors.
     */
    public <R2> DataResult<R2> flatMap(final Function<? super R, ? extends DataResult<R2>> function) {
        return create(result.map(
            l -> function.apply(l).get(),
            r -> Either.right(r.partialResult
                .map(value -> function.apply(value).get().map(
                    l2 -> new DynamicException<>(r.message, Optional.of(l2)),
                    r2 -> new DynamicException<>(r.message + "; " + r2.message, r2.partialResult)
                ))
                .orElseGet(
                    () -> new DynamicException<>(r.message, Optional.empty())
                )
            )
        ));
    }

    public DataResult<R> setPartial(final Supplier<R> partial) {
        return create(result.mapRight(r -> new DynamicException<>(r.message, Optional.of(partial.get()))));
    }

    public DataResult<R> setPartial(final R partial) {
        return create(result.mapRight(r -> new DynamicException<>(r.message, Optional.of(partial))));
    }

    public static Instance instance() {
        return Instance.INSTANCE;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final DataResult<?> that = (DataResult<?>) o;
        return Objects.equals(result, that.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result);
    }

    @Override
    public String toString() {
        return "DataResult[" + result + ']';
    }

    public static class DynamicException<R> {
        private final String message;
        private final Optional<R> partialResult;

        public DynamicException(final String message, final Optional<R> partialResult) {
            this.message = message;
            this.partialResult = partialResult;
        }

        public RuntimeException error() {
            return new RuntimeException(message);
        }

        public <R2> DynamicException<R2> map(final Function<? super R, ? extends R2> function) {
            return new DynamicException<>(message, partialResult.map(function));
        }

        public <R2> DynamicException<R2> flatMap(final Function<R, DynamicException<R2>> function) {
            if (partialResult.isPresent()) {
                final DynamicException<R2> result = function.apply(partialResult.get());
                return new DynamicException<>(message + "; " + result.message, result.partialResult);
            }
            return new DynamicException<>(message, Optional.empty());
        }

        public String message() {
            return message;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final DynamicException<?> that = (DynamicException<?>) o;
            return Objects.equals(message, that.message) && Objects.equals(partialResult, that.partialResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(message, partialResult);
        }

        @Override
        public String toString() {
            return "DynamicException[" + message + ' ' + partialResult + ']';
        }
    }

    public enum Instance implements Applicative<DataResult.Mu, DataResult.Instance.Mu> {
        INSTANCE;

        public static final class Mu implements Applicative.Mu {}

        @Override
        public <T, R> App<DataResult.Mu, R> map(final Function<? super T, ? extends R> func, final App<DataResult.Mu, T> ts) {
            return unbox(ts).map(func);
        }

        @Override
        public <A> App<DataResult.Mu, A> point(final A a) {
            return success(a);
        }

        @Override
        public <A, R> Function<App<DataResult.Mu, A>, App<DataResult.Mu, R>> lift1(final App<DataResult.Mu, Function<A, R>> function) {
            return fa -> ap(function, fa);
        }

        /** Argument error before the function */
        @Override
        public <A, R> App<DataResult.Mu, R> ap(final App<DataResult.Mu, Function<A, R>> func, final App<DataResult.Mu, A> arg) {
            return unbox(arg).flatMap(av ->
                unbox(func).map(f ->
                    f.apply(av)
                )
            );
        }

        @Override
        public <A, B, R> App<DataResult.Mu, R> ap2(final App<DataResult.Mu, BiFunction<A, B, R>> func, final App<DataResult.Mu, A> a, final App<DataResult.Mu, B> b) {
            return unbox(a).flatMap(av ->
                unbox(b).flatMap(bv ->
                    unbox(func).map(f ->
                        f.apply(av, bv)
                    )
                )
            );
        }

        @Override
        public <T1, T2, T3, R> App<DataResult.Mu, R> ap3(final App<DataResult.Mu, Function3<T1, T2, T3, R>> func, final App<DataResult.Mu, T1> t1, final App<DataResult.Mu, T2> t2, final App<DataResult.Mu, T3> t3) {
            return unbox(t1).flatMap(r1 ->
                unbox(t2).flatMap(r2 ->
                    unbox(t3).flatMap(r3 ->
                        unbox(func).map(f ->
                            f.apply(r1, r2, r3)
                        )
                    )
                )
            );
        }
    }
}