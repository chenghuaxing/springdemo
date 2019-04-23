package com.chx.springdemo.service.impl;

import com.chx.mvcframework.annotation.GPService;
import com.chx.springdemo.service.DemoService;

/**
 * @author cheng.huaxing
 * @date 2019-04-20
 */
@GPService
public class DemoServiceImpl implements DemoService {
    @Override
    public String get(String name) {
        return "My name is " + name;
    }
}
