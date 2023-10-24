# googleapis/java-spanner#2698

`AsyncRunner.runAsync` performs a [blocking get](https://github.com/googleapis/java-spanner/blob/v6.52.1/google-cloud-spanner/src/main/java/com/google/cloud/spanner/AsyncRunnerImpl.java#L60) inside its executor. This may result in deadlock if the following conditions are met:
* The executor is a thread pool with a maximum size.
* The thread pool is also used by the work provided to `runAsync`.
* The number of concurrent transactions is equal to or greater than the maximum number of threads.

This is most likely to manifest itself if the application has a constrained global thread pool optimized for running non-blocking operations. In particular, we noticed this in a `cats-effect` application, although the bug is not restricted to Scala or `cats-effect`. This is likely to be the underlying cause of #1751.

Our workaround is to provide a dedicated thread pool that is only used for `runAsync`, but which is not used by the work itself.

I've built a [minimum test case](https://github.com/zobar/ubiquitous-garbanzo/blob/main/app/src/main/java/com/jackhenry/dkleinschmidt/App.java) to demonstrate this issue.

#### Environment details

**OS type and version:** Mac OS Ventura 13.6
**Java version:** OpenJDK 21
**Versions:** `com.google.cloud:google-cloud-spanner:6.52.1`

#### Steps to reproduce

  1. Clone [my GitHub repo](https://github.com/zobar/ubiquitous-garbanzo) for a minimized test case.
  2. Run 15 concurrent transactions on a maximum of 16 threads. This will work as expected.
     ```
     $ ./gradlew run --args="16 15 my-project my-instance my-database"
     Getting results...
     Successfully ran 15 transactions in parallel on 16 threads.
     $
     ```
  2. Try running 16 concurrent transactions on a maximum of 16 threads. This will hang.
     ```
     $ ./gradlew run --args="16 16 my-project my-instance my-database"
     Getting results...
     ^C
     ```

#### Code example

```java
public int run() throws ExecutionException, InterruptedException {
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

public AsyncRunner.AsyncWork work(ApiFuture trigger) {
    return (txn) -> ApiFutures.transform(trigger, (input) -> input, threadPool);
}
```

#### External references such as API reference guides

The [API documentation](https://cloud.google.com/java/docs/reference/google-cloud-spanner/latest/com.google.cloud.spanner.AsyncRunner#com_google_cloud_spanner_AsyncRunner__R_runAsync_com_google_cloud_spanner_AsyncRunner_AsyncWork_R__java_util_concurrent_Executor_) for `runAsync` does not mention that it runs blocking operations in the executor.
