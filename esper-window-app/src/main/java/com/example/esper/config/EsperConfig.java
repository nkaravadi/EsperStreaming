package com.example.esper.config;

import com.espertech.esper.common.client.configuration.Configuration;
import com.espertech.esper.compiler.client.CompilerArguments;
import com.espertech.esper.compiler.client.EPCompilerProvider;
import com.espertech.esper.runtime.client.EPDeployment;
import com.espertech.esper.runtime.client.EPRuntime;
import com.espertech.esper.runtime.client.EPRuntimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class EsperConfig {
    private static final Logger log = LoggerFactory.getLogger(EsperConfig.class);

    private final Configuration configuration;
    private final EPRuntime runtime;

    public EsperConfig() {
        this.configuration = new Configuration();
        this.configuration.getCommon().addEventType("DummyInit", new String[]{"id"}, new Object[]{String.class});
        this.runtime = EPRuntimeProvider.getDefaultRuntime(configuration);
    }

    @Bean
    public EPRuntime epRuntime() {
        return runtime;
    }

    @Bean
    public Configuration esperConfiguration() {
        return configuration;
    }

    public EPDeployment compileAndDeploy(String epl) {
        try {
            var compiler = EPCompilerProvider.getCompiler();
            var args = new CompilerArguments(runtime.getConfigurationDeepCopy());
            args.getPath().add(runtime.getRuntimePath());
            log.debug("Compiling EPL (deployments in path: {}): {}",
                    runtime.getDeploymentService().getDeployments().length, epl);
            var compiled = compiler.compile(epl, args);
            return runtime.getDeploymentService().deploy(compiled);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile/deploy EPL: " + epl, e);
        }
    }
}
