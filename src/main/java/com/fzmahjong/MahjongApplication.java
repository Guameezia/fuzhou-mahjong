package com.fzmahjong;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 福州麻将应用主入口
 */
@SpringBootApplication
public class MahjongApplication {
    public static void main(String[] args) {
        SpringApplication.run(MahjongApplication.class, args);
        System.out.println("\n=================================");
        System.out.println("福州麻将服务器启动成功！");
        System.out.println("访问: http://localhost:8080");
        System.out.println("=================================\n");
    }
}
