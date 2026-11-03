package dev.myfrei.repopilot.http;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages="dev.myfrei.repopilot")
public class TaskHttpServerApplication {
    public static void main(String[] args) { SpringApplication.run(TaskHttpServerApplication.class,args); }
}
