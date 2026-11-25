package org.xcore.plugin.concurrent;

import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.when;

/**
 * A {@link StorageExecutor} that keeps each task until {@link #runAll()}, so a test can check what
 * the game thread did before storage answered. A task that throws completes its future
 * exceptionally, as on a real storage thread.
 */
public final class DeferredStorageExecutor {
    private final List<Runnable> pending = new ArrayList<>();
    private final StorageExecutor executor = Mockito.mock(StorageExecutor.class);

    public DeferredStorageExecutor() {
        when(executor.supply(ArgumentMatchers.<Callable<Object>>any())).thenAnswer(invocation -> {
            Callable<Object> task = invocation.getArgument(0);
            var future = new CompletableFuture<Object>();
            pending.add(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
            return future;
        });
    }

    public StorageExecutor executor() {
        return executor;
    }

    public boolean isIdle() {
        return pending.isEmpty();
    }

    /** Runs the waiting tasks, and any they queue, on the calling thread. */
    public void runAll() {
        while (!pending.isEmpty()) pending.removeFirst().run();
    }
}
