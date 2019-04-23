package com.chx.mvcframework.servlet.v3;

import com.chx.mvcframework.annotation.GPAutowired;
import com.chx.mvcframework.annotation.GPController;
import com.chx.mvcframework.annotation.GPRequestMapping;
import com.chx.mvcframework.annotation.GPRequestParam;
import com.chx.mvcframework.annotation.GPService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jdk.nashorn.api.scripting.ScriptUtils.convert;

/**
 * @author cheng.huaxing
 * @date 2019-04-20
 */
public class GPDispatcherServlet extends HttpServlet {

    /**
     * 保存配置文件application.properties的内容
     * */
    private Properties contextConfig = new Properties();

    /**
     * 保存所有的类名
     */
    private List<String> classNames = new ArrayList<>();

    /**
     * ioc容器
     */
    private Map<String, Object> ioc = new HashMap<>();

    /**
     * 保存URL与method的对应关系
     */
    private List<Handler> handleMapping = new ArrayList<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            // 6、调用，运行阶段
            doDispatch(req, resp);
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException, InvocationTargetException, IllegalAccessException {
        Handler handler = getHandler(req);
        if (handler == null) {
            resp.getWriter().write("404 Not Found!");
            return;
        }

        // 获取方法的形参列表
        Class<?>[] parameterTypes = handler.method.getParameterTypes();
        // 方法调用的参数
        Object[] paramValues = new Object[parameterTypes.length];
        // url传递的参数
        Map<String, String[]> params = req.getParameterMap();
        for (Map.Entry<String, String[]> param : params.entrySet()) {
            // 获取参数值
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]","")
                    .replaceAll("\\s",",");

            if (!handler.paramIndexMapping.containsKey(param.getKey())) {
                continue;
            }
            int index = handler.paramIndexMapping.get(param.getKey());
            // 参数类型转换
            paramValues[index] = convert(parameterTypes[index], value);
        }

        if(handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())) {
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
        }

        if(handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())) {
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;
        }

        handler.method.invoke(handler.controller, paramValues);
    }

    /**
     * url传过来的参数都是String类型的，HTTP是基于字符串协议
     * 只需要把String转换为任意类型就好
     * @param type
     * @param value
     * @return
     */
    private Object convert(Class<?> type, String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        else if(Double.class == type){
            return Double.valueOf(value);
        }
        //如果还有其他类型，继续加if
        //可以使用策略模式
        return value;
    }

    private Handler getHandler(HttpServletRequest req) {
        if (handleMapping.isEmpty()) {
            return null;
        }

        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        // 相对路径
        url = url.replace(contextPath, "").replaceAll("/+", "/");

        for (Handler handler : handleMapping) {
            try {
                Matcher matcher = handler.pattern.matcher(url);
                if (!matcher.matches()) {
                    continue;
                }
                return handler;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        // 1、加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        // 2、扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));

        // 3、初始化扫描到的类，并将它们放入到ICO容器中
        doInstance();

        // 4、完成依赖注入
        doAutowired();

        // 5、初始化HandleMapping
        initHandleMapping();

        System.out.println("GP Spring MVC initialized");
    }

    private void doLoadConfig(String contextConfigLocation) {
        // 在类路径中找到spring配置文件，并将其读取出来加载到Properties对象中
        try (InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation)) {
            contextConfig.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void doScanner(String scanPackage) {
        // 包路径转换为文件路径
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            if (file.isDirectory()) {
                // 递归下一个目录
                doScanner(scanPackage + "." + file.getName());
            } else {
                if (!file.getName().endsWith(".class")) {
                    continue;
                }
                String className = scanPackage + "." + file.getName().replace(".class", "");
                classNames.add(className);
            }
        }
    }

    private void doInstance() {
        if (classNames.isEmpty()) {
            return;
        }
        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                // 初始化添加类注解的类
                if (clazz.isAnnotationPresent(GPController.class)) {
                    Object instance = clazz.newInstance();
                    // Spring默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                } else if (clazz.isAnnotationPresent(GPService.class)) {
                    // 自定义beanName
                    GPService service = clazz.getAnnotation(GPService.class);
                    String beanName = service.value();

                    // 默认类名首字母小写
                    if ("".equals(beanName)) {
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);

                    // 根据类型自动赋值（投机取巧的方式）
                    for (Class<?> anInterface : clazz.getInterfaces()) {
                        if (ioc.containsKey(anInterface.getName())) {
                            // 接口有多个实现
                            throw new Exception("The " + anInterface.getName() + "is exist！");
                        }
                        ioc.put(anInterface.getName(), instance);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            // 获取对象所有的字段
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(GPAutowired.class)) {
                    continue;
                }
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);
                // 如果没有指定beanName默认按类型注入
                String beanName = autowired.value();
                if ("".equals(beanName)) {
                    // 获取类型名称，从ioc容器中取值
                    beanName = field.getType().getName();
                } else {
                    beanName = toLowerFirstCase(beanName);
                }
                field.setAccessible(true);

                try {
                    // 反射动态给字段赋值
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private void initHandleMapping() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();

            if (!clazz.isAnnotationPresent(GPController.class)) {
                continue;
            }
            // 保存controller类上RequestMapping的值
            String baseUrl = "";
            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            // 获取所有public方法
            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(GPRequestMapping.class)) {
                    continue;
                }
                GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);
                String url = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(url);
                handleMapping.add(new Handler(entry.getValue(), method, pattern));
            }
        }
    }


    /**
     * Handler记录Controller中RequestMapping和method的对应关系
     */
    private class Handler {
        /**
         * 保存方法对应的实例
         */
        private Object controller;

        /**
         * 保存url映射的方法
         */
        private Method method;

        /**
         * 保存正则表达式
         */
        private Pattern pattern;

        /**
         * 保存参数顺序
         */
        private Map<String, Integer> paramIndexMapping;

        public Handler(Object controller, Method method, Pattern pattern) {
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;
            paramIndexMapping = new HashMap<>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method) {
            // 获取方法中加了注解的参数
            Annotation[][] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length; i++) {
                Annotation[] as = pa[i];
                for (Annotation a : as) {
                    if (a instanceof GPRequestParam) {
                        String paramName = ((GPRequestParam) a).value();
                        if (!"".equals(paramName.trim())) {
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }
            // 获取方法中的request和response参数
            Class<?>[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                if (paramTypes[i] == HttpServletRequest.class || paramTypes[i] == HttpServletResponse.class) {
                    paramIndexMapping.put(paramTypes[i].getName(), i);
                }
            }
        }

    }

}
