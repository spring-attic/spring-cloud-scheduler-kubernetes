/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.scheduler.spi.kubernetes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.batch.CronJob;
import org.springframework.boot.bind.YamlConfigurationFactory;
import org.springframework.cloud.scheduler.spi.core.ScheduleRequest;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.cloud.scheduler.spi.kubernetes.KubernetesSchedulerProperties.KUBERNETES_SCHEDULER_PROPERTIES;

/**
 * Configures a {@link Container} that will be launched by a {@link CronJob}.
 *
 * @author Chris Schaefer
 */
class ContainerCreator {
	private final ScheduleRequest scheduleRequest;

	private final KubernetesSchedulerProperties kubernetesSchedulerProperties;

	ContainerCreator(ScheduleRequest scheduleRequest) {
		this(new KubernetesSchedulerProperties(), scheduleRequest);
	}

	public ContainerCreator(KubernetesSchedulerProperties kubernetesSchedulerProperties,
			ScheduleRequest scheduleRequest) {
		Assert.notNull(scheduleRequest, "ScheduleRequest must not be null");
		Assert.notNull(kubernetesSchedulerProperties, "KubernetesSchedulerProperties must not be null");
		Assert.hasText(scheduleRequest.getScheduleName(), "ScheduleRequest must contain schedule name");

		this.scheduleRequest = scheduleRequest;
		this.kubernetesSchedulerProperties = kubernetesSchedulerProperties;
	}

	public Container build() {
		String imagePullPolicy = KubernetesSchedulerPropertyResolver.getImagePullPolicy(this.scheduleRequest,
				this.kubernetesSchedulerProperties);

		return new ContainerBuilder()
				.withName(this.scheduleRequest.getScheduleName())
				.withImage(getImage())
				.withImagePullPolicy(imagePullPolicy)
				.withEnv(getContainerParameters().getEnvironmentVariables())
				.withArgs(getContainerParameters().getCommandLineArguments())
				.withVolumeMounts(getVolumeMounts(this.scheduleRequest))
				.build();
	}

	private String getImage() {
		String image;

		try {
			image = this.scheduleRequest.getResource().getURI().getSchemeSpecificPart();
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Unable to get image name from: " +
					this.scheduleRequest.getResource(), e);
		}

		return image;
	}

	private ContainerParameters getContainerParameters() {
		List<EnvVar> environmentVariables = new ArrayList<>();
		List<String> commandLineArguments = new ArrayList<>();

		Map<String, String> envVarsMap = new HashMap<>();
		envVarsMap.putAll(KubernetesSchedulerPropertyResolver.getTaskEnvironmentVariables(this.scheduleRequest,
				this.kubernetesSchedulerProperties));

		environmentVariables.addAll(envVarsMap.entrySet().stream().map(e -> new EnvVar(e.getKey(), e.getValue(), null))
				.collect(Collectors.toList()));

		EntryPointStyle entryPointStyle = KubernetesSchedulerPropertyResolver.getEntryPointStyle(this.scheduleRequest,
				this.kubernetesSchedulerProperties);

		switch (entryPointStyle) {
		case exec:
			commandLineArguments.addAll(createCommandLineArguments());

			break;
		case boot:
			try {
				environmentVariables.add(new EnvVar("SPRING_APPLICATION_JSON",
						new ObjectMapper().writeValueAsString(this.scheduleRequest.getDefinition().getProperties()),
						null));
			}
			catch (JsonProcessingException e) {
				throw new IllegalStateException("Unable to create SPRING_APPLICATION_JSON", e);
			}

			commandLineArguments.addAll(this.scheduleRequest.getCommandlineArguments());

			break;
		case shell:
			environmentVariables.addAll(createEnvironmentVariables());

			break;
		}

		return new ContainerParameters(environmentVariables, commandLineArguments);
	}

	protected List<String> createCommandLineArguments() {
		List<String> commandLineArguments = new ArrayList<>();
		Map<String, String> applicationProperties = this.scheduleRequest.getDefinition().getProperties();

		commandLineArguments.addAll(applicationProperties
				.entrySet()
				.stream()
				.map(entry -> String.format("--%s=%s", entry.getKey(), entry.getValue()))
				.collect(Collectors.toList()));

		commandLineArguments.addAll(this.scheduleRequest.getCommandlineArguments());

		return commandLineArguments;
	}

	protected List<EnvVar> createEnvironmentVariables() {
		List<EnvVar> environmentVariables = new ArrayList<>();

		for (String environmentVariable : this.scheduleRequest.getDefinition().getProperties().keySet()) {
			String transformedEnvironmentVariable = environmentVariable.replace('.', '_').toUpperCase();

			environmentVariables.add(new EnvVar(transformedEnvironmentVariable,
					this.scheduleRequest.getDefinition().getProperties().get(environmentVariable), null));
		}

		return environmentVariables;
	}

	private static class ContainerParameters {
		private final List<EnvVar> environmentVariables;

		private final List<String> commandLineArguments;

		ContainerParameters(List<EnvVar> environmentVariables, List<String> commandLineArguments) {
			this.environmentVariables = environmentVariables != null ? environmentVariables : Collections.emptyList();
			this.commandLineArguments = commandLineArguments != null ? commandLineArguments : Collections.emptyList();
		}

		List<EnvVar> getEnvironmentVariables() {
			return this.environmentVariables;
		}

		List<String> getCommandLineArguments() {
			return this.commandLineArguments;
		}
	}

	/**
	 * Volume mount deployment properties are specified in YAML format:
	 * <p>
	 * <code>
	 * spring.cloud.scheduler.kubernetes.volumeMounts=[{name: 'testhostpath', mountPath: '/test/hostPath'},
	 * {name: 'testpvc', mountPath: '/test/pvc'}, {name: 'testnfs', mountPath: '/test/nfs'}]
	 * </code>
	 * <p>
	 * Volume mounts can be specified as scheduler properties as well as app deployment properties.
	 * Deployment properties override scheduler properties.
	 *
	 * @param request the {@link ScheduleRequest}
	 * @return the configured volume mounts
	 */
	protected List<VolumeMount> getVolumeMounts(ScheduleRequest request) {
		List<VolumeMount> volumeMounts = new ArrayList<>();

		String volumeMountDeploymentProperty = request.getDeploymentProperties()
				.getOrDefault(KUBERNETES_SCHEDULER_PROPERTIES + ".volumeMounts", "");
		if (!StringUtils.isEmpty(volumeMountDeploymentProperty)) {
			YamlConfigurationFactory<KubernetesSchedulerProperties> volumeMountYamlConfigurationFactory = new YamlConfigurationFactory<>(
					KubernetesSchedulerProperties.class);
			volumeMountYamlConfigurationFactory.setYaml("{ volumeMounts: " + volumeMountDeploymentProperty + " }");
			try {
				volumeMountYamlConfigurationFactory.afterPropertiesSet();
				volumeMounts.addAll(volumeMountYamlConfigurationFactory.getObject().getVolumeMounts());
			} catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid volume mount '%s'", volumeMountDeploymentProperty), e);
			}
		}
		// only add volume mounts that have not already been added, based on the volume mount's name
		// i.e. allow provided deployment volume mounts to override deployer defined volume mounts
		volumeMounts.addAll(kubernetesSchedulerProperties.getVolumeMounts().stream().filter(volumeMount -> volumeMounts.stream()
				.noneMatch(existingVolumeMount -> existingVolumeMount.getName().equals(volumeMount.getName())))
				.collect(Collectors.toList()));
		return volumeMounts;
	}
}
