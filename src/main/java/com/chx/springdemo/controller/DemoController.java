package com.chx.springdemo.controller;

import com.chx.mvcframework.annotation.GPAutowired;
import com.chx.mvcframework.annotation.GPController;
import com.chx.mvcframework.annotation.GPRequestMapping;
import com.chx.mvcframework.annotation.GPRequestParam;
import com.chx.springdemo.service.DemoService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @author cheng.huaxing
 * @date 2019-04-20
 */
@GPController
@GPRequestMapping("/demo")
public class DemoController {

    @GPAutowired
    private DemoService demoService;

    @GPRequestMapping("/query")
    public void query(HttpServletRequest request, HttpServletResponse response, @GPRequestParam("name") String name) {
        String result = demoService.get(name);
        try {
            response.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
