package com.ecommerce.inventory.infrastructure.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} methods (the outbound retry worker). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {}
