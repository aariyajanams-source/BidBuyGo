package com.bidbuygo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.bidbuygo.common.error.ApiException;
import org.springframework.dao.DataIntegrityViolationException;

/** Runs tasks on separate threads released at the same instant by a shared latch. */
public final class Race {

    public static final int OK = 201;

    private Race() {
    }

    public static <T> List<T> run(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs the action and maps the outcome to an HTTP-style status: 201, the ApiException status, or 409. */
    public static int status(Runnable action) {
        try {
            action.run();
            return OK;
        } catch (ApiException e) {
            return e.getStatus().value();
        } catch (DataIntegrityViolationException e) {
            return 409;
        }
    }
}
