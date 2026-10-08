package com.jiangpa.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiangpa.annotation.RequireRole;
import com.jiangpa.common.Result;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class AuthorizationInterceptor implements HandlerInterceptor {

    private static final String ATTR_ROLE = "role";
    private final ObjectMapper objectMapper;

    public AuthorizationInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception{

        if (!(handler instanceof HandlerMethod handlerMethod)) return true;

        if (!handlerMethod.hasMethodAnnotation(RequireRole.class)) return true;

        Number role = (Number) request.getAttribute(ATTR_ROLE);
        if (role == null) {
            writeForbidden(response, "权限错误！");
            return false;
        }

        if(role.intValue() != 1){
            writeForbidden(response, "无权限！");
            return false;
        }

        return true;
    }

    /**
     * 往响应里写 401 和统一的 Result 结构。
     *
     * 这是本类最关键的一处：preHandle 返回 false 只是告诉 Spring「别往下走了」，
     * 它不会帮你生成任何响应内容。如果不自己写，前端收到的是一个内容为空的 HTTP 200，
     * 既不是错误码也没有错误信息，排查起来非常费劲。
     */
    private void writeForbidden(HttpServletResponse response, String message) throws IOException {

        // HTTP 状态码设为 200
        response.setStatus(HttpServletResponse.SC_OK);

        // 必须带 charset=UTF-8，否则中文提示到前端会变乱码
        response.setContentType("application/json;charset=UTF-8");

        // 用注入的 ObjectMapper 序列化，不要手拼 JSON 字符串——字段名或转义出问题很难查
        response.getWriter().write(objectMapper.writeValueAsString(Result.forbidden(message)));
    }
}
