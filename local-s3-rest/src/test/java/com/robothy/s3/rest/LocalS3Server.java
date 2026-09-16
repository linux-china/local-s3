package com.robothy.s3.rest;

import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import java.util.concurrent.CountDownLatch;

public class LocalS3Server {
    public static void main(String[] args) throws Exception {
        LocalS3 localS3 = LocalS3.builder()
                .mode(LocalS3Mode.PERSISTENCE)
                .dataPath("temp/local-s3-data")
                .credentials("admin","admin")
                .buckets("msst-test","demo1", "demo2", "demo3")
                .changeListener(change -> {
                    System.out.println(change.type() + ":s3://" + change.bucketName() + "/" + change.key());
                })
                .build();
        localS3.start();
        System.out.println("port: "+localS3.getPort());
        CountDownLatch latch = new CountDownLatch(1);
        latch.await();
        localS3.close();
    }
}
