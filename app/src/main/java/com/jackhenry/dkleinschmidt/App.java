package com.jackhenry.dkleinschmidt;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.cloud.spanner.AsyncRunner;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

public class App implements AutoCloseable {

    DatabaseClient databaseClient;
    Spanner spanner;
    ExecutorService threadPool;

    public App(DatabaseId databaseId, int maxThreads) {
        spanner = SpannerOptions.getDefaultInstance().getService();
        databaseClient = spanner.getDatabaseClient(databaseId);
        threadPool = Executors.newWorkStealingPool(maxThreads);
    }

    //
    // Does no useful work, but it does schedule a task on the shared thread
    // pool.
    //
    public AsyncRunner.AsyncWork work(ApiFuture trigger) {
        return (txn) -> ApiFutures.transform(trigger, (input) -> input, threadPool);
    }

    //
    // Spawns several concurrent tranasctions and completes them all
    // simultaneously. If concurrency >= maxThreads, this method will hang.
    // AsyncRunnerImpl.runTransaction performs a blocking get, thus starving the
    // thread pool. If the work provided to runAsync shares the same thread
    // pool, this will result in deadlock.
    //
    // Workaround: provide a dedicated thread pool to runAsync.
    //
    public int run(int concurrency)
            throws ExecutionException, InterruptedException {
        SettableApiFuture trigger = SettableApiFuture.create();

        Iterable transactions = Stream
                .generate(() -> databaseClient
                        .runAsync()
                        .runAsync(work(trigger), threadPool))
                .limit(concurrency)
                .toList();
        ApiFuture<List> results = ApiFutures.allAsList(transactions);

        trigger.set(null);

        System.out.println("Getting results...");
        return results.get().size();
    }

    public void close() {
        threadPool.close();
        spanner.close();
    }

    //
    // Usage:
    //
    // ./gradlew run --args="maxThreads concurrency projectId instanceId databaseId"
    //
    // Examples:
    //
    // $ ./gradlew run --args="16 15 my-project my-instance my-database"
    // Getting results...
    // Successfully ran 15 transactions in parallel on 16 threads.
    // $
    //
    // $ ./gradlew run --args="16 16 my-project my-instance my-database"
    // Getting results...
    // ^C
    //
    public static void main(String[] args) {
        try {
            int maxThreads = Integer.parseInt(args[0]);
            int concurrency = Integer.parseInt(args[1]);
            DatabaseId databaseId = DatabaseId.of(args[2], args[3], args[4]);

            try (App app = new App(databaseId, maxThreads)) {
                int results = app.run(concurrency);
                System.out.println("Successfully ran "
                        + results
                        + " transactions in parallel on "
                        + maxThreads
                        + " threads.");
            } catch (ExecutionException execution) {
                System.err.println("Execution exception:");
                execution.getCause().printStackTrace();
            } catch (InterruptedException interrupted) {
                System.err.println("Interrupted!");
            }
        } catch (NumberFormatException numberFormat) {
            System.err.println("maxThreads and concurrency must be integers");
        } catch (ArrayIndexOutOfBoundsException arrayIndexOutOfBounds) {
            System.err.println("Usage:");
            System.err.println("./gradlew run --args=\"maxThreads concurrency projectId instanceId databaseId\"");
        }
    }
}
