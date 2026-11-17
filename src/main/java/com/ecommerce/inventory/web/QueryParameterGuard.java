package com.ecommerce.inventory.web;

import com.ecommerce.inventory.web.dto.ParameterError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects a paging parameter that is empty ({@code ?limit=}) or given more than once
 * ({@code ?limit=1&limit=2}) before the request reaches the controller (US-04 FR-010, research R5).
 * Spring would otherwise silently apply the default or use the first value.
 */
public class QueryParameterGuard implements HandlerInterceptor {

    /** The guarded query parameters. */
    static final List<String> PAGING_PARAMETERS = List.of("limit", "offset");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        List<ParameterError> errors = new ArrayList<>();
        for (String parameter : PAGING_PARAMETERS) {
            String[] values = request.getParameterValues(parameter);
            if (values == null) {
                continue;
            }
            if (values.length > 1) {
                errors.add(new ParameterError(parameter, String.join(",", values), "must be given at most once"));
            } else if (values[0].isBlank()) {
                errors.add(new ParameterError(parameter, values[0], "must not be empty"));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidQueryParameterException(errors);
        }
        return true;
    }
}
