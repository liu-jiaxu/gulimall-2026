package com.atguigu.gulimall.search;

import java.util.concurrent.*;

/**
 * @ClassName: ThreadTest
 * @Package: com.atguigu.gulimall.search
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/28 - 19:33
 * @Version: v1.0
 */
public class ThreadTest {

    public static ExecutorService executor = Executors.newFixedThreadPool(10);

    public static void main(String[] args) throws ExecutionException, InterruptedException {
//        1. 继承Thread  extends Thread
//         System.out.println("main......start.....");
//         Thread thread = new Thread01();
//         thread.start();
//         System.out.println("main......end.....");

//        2. 实现Runnable接口 implements Runnable
//         Runable01 runable01 = new Runable01();
//         new Thread(runable01).start();

//        3.实现Callable接口  implements Callable<Integer>
//         FutureTask<Integer> futureTask = new FutureTask<>(new Callable01());
//         new Thread(futureTask).start();
//       阻塞  等待整个线程执行完成，获取返回结果
//        Integer integer = futureTask.get();
//        System.out.println("main...end..."+integer);

//        我们以后在业务代码里面，以上三种启动线程的方式都不用。【将所有的线程异步任务都交给线程池执行】
//        4. 线程池 ExecutorService
//        区别
//        1、2不能得到返回值，3可以获取返回值
//        1 2 3 都不能控制资源
//        4 可以控制资源,性能稳定

//         service.execute(new Runable01());
//         Future<Integer> submit = service.submit(new Callable01());
//         submit.get();

        System.out.println("main......start.....");
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            System.out.println("当前线程：" + Thread.currentThread().getId());
            int i = 10 / 2;
            System.out.println("运行结果：" + i);
        }, executor);

        /**
         * 方法完成后的处理
         */
        // CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
        //     System.out.println("当前线程：" + Thread.currentThread().getId());
        //     int i = 10 / 0;
        //     System.out.println("运行结果：" + i);
        //     return i;
        // }, executor).whenComplete((res,exception) -> {
        //     //虽然能得到异常信息，但是没法修改返回数据
        //     System.out.println("异步任务成功完成了...结果是：" + res + "异常是：" + exception);
        // }).exceptionally(throwable -> {
        //     //可以感知异常，同时返回默认值
        //     return 10;
        // });

        /**
         * 方法执行完后端处理
         */
        // CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
        //     System.out.println("当前线程：" + Thread.currentThread().getId());
        //     int i = 10 / 2;
        //     System.out.println("运行结果：" + i);
        //     return i;
        // }, executor).handle((result,thr) -> {
        //     if (result != null) {
        //         return result * 2;
        //     }
        //     if (thr != null) {
        //         System.out.println("异步任务成功完成了...结果是：" + result + "异常是：" + thr);
        //         return 0;
        //     }
        //     return 0;
        // });


        /**
         * 线程串行化
         * 1、thenRunL：不能获取上一步的执行结果
         * 2、thenAcceptAsync：能接受上一步结果，但是无返回值
         * 3、thenApplyAsync：能接受上一步结果，有返回值
         *
         */
//        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
//            System.out.println("当前线程：" + Thread.currentThread().getId());
//            int i = 10 / 2;
//            System.out.println("运行结果：" + i);
//            return i;
//        }, executor).thenApplyAsync(res -> {
//            System.out.println("任务2启动了..." + res);
//            return "Hello" + res;
//        }, executor);
//        System.out.println("main......end....." + future.get());

//        CompletableFuture接口
//        CompletableFuture接口是Java 8引入的一个功能强大的异步编程工具，它扩展了Future接口，提供了更多的功能和灵活性。
//        CompletableFuture允许你以非阻塞的方式处理异步计算，并且支持链式调用和组合多个异步任务。
//
//        初始化：
//        - CompletableFuture.supplyAsync(Runnable, Executor可选，自定义线程池): 创建一个异步计算任务，并返回一个CompletableFuture对象。
//        - CompletableFuture.runAsync(Runnable, Executor): 创建一个异步执行的Runnable任务，并返回一个CompletableFuture对象。
//
//        CompletableFuture接口的主要方法包括：
//        - thenApply(): 在异步计算完成后，对结果进行转换。
//        - thenAccept(): 在异步计算完成后，对结果进行消费。
//        - thenRun(): 在异步计算完成后，执行一个Runnable任务。
//        - whenComplete(): 在异步计算完成后，提供一个处理结果和异常的函数。
//        - exceptionally(): 在异步计算过程中发生异常时，提供一个处理异常的函数。
//        - allOf(): 等待多个CompletableFuture都完成。
//        - anyOf(): 等待任意一个CompletableFuture完成。
//        - handle(): 在异步计算完成后，提供一个处理结果和异常的函数。
//        - thenCombine(): 将两个CompletableFuture的结果进行组合。
//        - thenCompose(): 将一个CompletableFuture的结果作为另一个CompletableFuture的输入。
//        - complete(): 手动完成一个CompletableFuture，设置其结果或异常。
//        - completeExceptionally(): 手动完成一个CompletableFuture，设置一个异常。
//        - obtrudeValue(): 强制设置一个CompletableFuture的结果，无论它是否已经完成。
//        - obtrudeException(): 强制设置一个CompletableFuture的异常，无论它是否已经完成。
//        - getNow(): 获取异步计算的结果，如果计算尚未完成，则返回一个默认值。
//        - get(): 获取异步计算的结果，如果计算尚未完成，则会阻塞当前线程直到计算完成。
//        - join(): 获取异步计算的结果，如果计算尚未完成，则会阻塞当前线程直到计算完成（无需捕获异常）。

    }

    private static void threadPool() {

        /**
         * 七大参数:
         * int corePoolSize  核心线程数,一直存在除非allowCoreThreadTimeOut
         * int maximumPoolSize: 最大线程数量
         * long keepAliveTime: 存活时间,如果当前的的线程数量大于core数量,释放空闲的线程
         * TimeUnit unit: 时间单位
         * BlockingQueue<Runnable> workQueue:阻塞队列,只要线程有空闲,就回去队列里面取出新的任务继续执行
         * ThreadFactory threadFactory: 线程的创建工程
         * RejectedExecutionHandler handler: 如果队列满了,按照我们指定的拒绝策略执行任务
         *
         * 工作顺序
         * 1.线程池,创建,准备好core数量的核心线程,准备接收任务
         * 2.阻塞队列满了,就直接开始新线程执行,最大只能开到max指定的数量
         * 3.max满了就用RejectedExecutionHandler拒绝任务
         * 4.max都执行完成,有很多空闲,在指定时间keepAliveTime以后,释放max-core线程
         *
         * 一个线程池 core 7,max 20, queue 50,100并发进来怎样分配
         * 7个会立即执行: 50个会进入队列,再开13个进行执行,剩下30个就使用拒绝策略.
         *
         */
        ExecutorService threadPool = new ThreadPoolExecutor(
                200,
                10,
                10L,
                TimeUnit.SECONDS,
                new LinkedBlockingDeque<Runnable>(10000),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
//        Executors.newCachedThreadPool()  core时是0,所有都要回收你
//        Executors.newFixedThreadPool() 固定大小,core=max 都不可回收
//        Executors.newScheduledThreadPool() 定时任务线程池
        //定时任务的线程池
        ExecutorService service = Executors.newScheduledThreadPool(2);
    }


    public static class Thread01 extends Thread {
        @Override
        public void run() {
            System.out.println("当前线程：" + Thread.currentThread().getId());
            int i = 10 / 2;
            System.out.println("运行结果：" + i);
        }
    }


    public static class Runable01 implements Runnable {
        @Override
        public void run() {
            System.out.println("当前线程：" + Thread.currentThread().getId());
            int i = 10 / 2;
            System.out.println("运行结果：" + i);
        }
    }


    public static class Callable01 implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            System.out.println("当前线程：" + Thread.currentThread().getId());
            int i = 10 / 2;
            System.out.println("运行结果：" + i);
            return i;
        }
    }

}
