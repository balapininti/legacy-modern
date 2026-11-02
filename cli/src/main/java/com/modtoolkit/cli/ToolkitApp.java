package com.modtoolkit.cli;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The one Spring application behind every command and service profile. */
@SpringBootApplication(scanBasePackages = "com.modtoolkit")
public class ToolkitApp {
    public static ConfigurableApplicationContext batch() {
        return new SpringApplicationBuilder(ToolkitApp.class).profiles("batch").web(WebApplicationType.NONE).logStartupInfo(false).run();
    }
}
