package com.yhh.yojcodesanbox.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author hyh
 * @date 2024/6/5
 */
@RestController
@RequestMapping("/")
public class Controller {

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }
}
