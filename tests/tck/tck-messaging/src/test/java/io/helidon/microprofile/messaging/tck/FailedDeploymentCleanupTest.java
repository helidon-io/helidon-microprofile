/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.microprofile.messaging.tck;

import org.eclipse.microprofile.reactive.messaging.tck.SimpleIncomingTest;
import org.eclipse.microprofile.reactive.messaging.tck.connector.MissingConnectorTest;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

@RunWith(Arquillian.class)
@RunAsClient
public class FailedDeploymentCleanupTest {
    @ArquillianResource
    private Deployer deployer;

    private boolean deployed;

    @Deployment(name = "missing-connector", managed = false, testable = false)
    public static Archive<JavaArchive> missingConnector() {
        return MissingConnectorTest.missingConnectorDeployment();
    }

    @Deployment(name = "valid", managed = false, testable = false)
    public static Archive<JavaArchive> valid() {
        return SimpleIncomingTest.deployment();
    }

    @After
    public void undeploy() {
        if (this.deployed) {
            this.deployer.undeploy("valid");
        }
    }

    @Test
    public void canDeployAfterMissingConnector() {
        DeploymentException failure = assertThrows(DeploymentException.class,
                                                    () -> this.deployer.deploy("missing-connector"));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause.getMessage(), is("No connector Dummy found!"));

        // Both deployments use the same configured port in the same JVM.
        this.deployer.deploy("valid");
        this.deployed = true;
    }
}
