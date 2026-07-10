package com.tasksphere;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // enables @Scheduled on NotificationService cleanup
public class TaskSphereApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaskSphereApplication.class, args);
    }
}
