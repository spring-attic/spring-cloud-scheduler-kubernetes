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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.StatusCause;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.batch.CronJob;
import io.fabric8.kubernetes.api.model.batch.CronJobBuilder;
import io.fabric8.kubernetes.api.model.batch.CronJobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.boot.bind.YamlConfigurationFactory;
import org.springframework.cloud.scheduler.spi.core.CreateScheduleException;
import org.springframework.cloud.scheduler.spi.core.ScheduleInfo;
import org.springframework.cloud.scheduler.spi.core.ScheduleRequest;
import org.springframework.cloud.scheduler.spi.core.Scheduler;
import org.springframework.cloud.scheduler.spi.core.SchedulerException;
import org.springframework.cloud.scheduler.spi.core.SchedulerPropertyKeys;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kubernetes implementation of the {@link Scheduler} SPI.
 *
 * @author Chris Schaefer
 */
public class KubernetesScheduler implements Scheduler {
	private static final String SPRING_CRONJOB_ID_KEY = "spring-cronjob-id";

	private static final String SCHEDULE_EXPRESSION_FIELD_NAME = "spec.schedule";

	private final KubernetesClient kubernetesClient;

	private final KubernetesSchedulerProperties kubernetesSchedulerProperties;

	public KubernetesScheduler(KubernetesClient kubernetesClient,
			KubernetesSchedulerProperties kubernetesSchedulerProperties) {
		Assert.notNull(kubernetesClient, "KubernetesClient must not be null");
		Assert.notNull(kubernetesSchedulerProperties, "KubernetesSchedulerProperties must not be null");

		this.kubernetesClient = kubernetesClient;
		this.kubernetesSchedulerProperties = kubernetesSchedulerProperties;
	}

	@Override
	public void schedule(ScheduleRequest scheduleRequest) {
		try {
			createCronJob(scheduleRequest);
		}
		catch (KubernetesClientException e) {
			String invalidCronExceptionMessage = getExceptionMessageForField(e, SCHEDULE_EXPRESSION_FIELD_NAME);

			if (StringUtils.hasText(invalidCronExceptionMessage)) {
				throw new CreateScheduleException(invalidCronExceptionMessage, e);
			}

			throw new CreateScheduleException("Failed to create schedule " + scheduleRequest.getScheduleName(), e);
		}
	}

	@Override
	public void unschedule(String scheduleName) {
		boolean unscheduled = this.kubernetesClient.batch().cronjobs().withName(scheduleName).delete();

		if (!unscheduled) {
			throw new SchedulerException("Failed to unschedule schedule " + scheduleName + " does not exist.");
		}
	}

	@Override
	public List<ScheduleInfo> list(String taskDefinitionName) {
		return list()
				.stream()
				.filter(scheduleInfo -> taskDefinitionName.equals(scheduleInfo.getTaskDefinitionName()))
				.collect(Collectors.toList());
	}

	@Override
	public List<ScheduleInfo> list() {
		CronJobList cronJobList = this.kubernetesClient.batch().cronjobs().list();

		List<CronJob> cronJobs = cronJobList.getItems();
		List<ScheduleInfo> scheduleInfos = new ArrayList<>();

		for (CronJob cronJob : cronJobs) {
			Map<String, String> properties = new HashMap<>();
			properties.put(SchedulerPropertyKeys.CRON_EXPRESSION, cronJob.getSpec().getSchedule());

			ScheduleInfo scheduleInfo = new ScheduleInfo();
			scheduleInfo.setScheduleName(cronJob.getMetadata().getName());
			scheduleInfo.setTaskDefinitionName(cronJob.getMetadata().getLabels().get(SPRING_CRONJOB_ID_KEY));
			scheduleInfo.setScheduleProperties(properties);

			scheduleInfos.add(scheduleInfo);
		}

		return scheduleInfos;
	}

	protected CronJob createCronJob(ScheduleRequest scheduleRequest) {
		Map<String, String> labels = Collections.singletonMap(SPRING_CRONJOB_ID_KEY,
				scheduleRequest.getDefinition().getName());

		String schedule = scheduleRequest.getSchedulerProperties().get(SchedulerPropertyKeys.CRON_EXPRESSION);
		Assert.hasText(schedule, "The property: " + SchedulerPropertyKeys.CRON_EXPRESSION + " must be defined");

		Container container = new ContainerCreator(this.kubernetesSchedulerProperties, scheduleRequest).build();

		String taskServiceAccountName = KubernetesSchedulerPropertyResolver.getTaskServiceAccountName(scheduleRequest,
				this.kubernetesSchedulerProperties);

		String restartPolicy = this.kubernetesSchedulerProperties.getRestartPolicy().name();

		CronJob cronJob = new CronJobBuilder().withNewMetadata().withName(scheduleRequest.getScheduleName())
				.withLabels(labels).endMetadata().withNewSpec().withSchedule(schedule).withNewJobTemplate()
				.withNewSpec().withNewTemplate().withNewSpec().withServiceAccountName(taskServiceAccountName)
					.withVolumes(getVolumes(scheduleRequest).stream()
							.filter(volume -> container.getVolumeMounts().stream()
									.anyMatch(volumeMount -> volumeMount.getName().equals(volume.getName())))
							.collect(Collectors.toList()))
				.withContainers(container).withRestartPolicy(restartPolicy).endSpec().endTemplate().endSpec()
				.endJobTemplate().endSpec().build();

		setImagePullSecret(scheduleRequest, cronJob);

		return this.kubernetesClient.batch().cronjobs().create(cronJob);
	}

	protected String getExceptionMessageForField(KubernetesClientException kubernetesClientException,
			String fieldName) {
		List<StatusCause> statusCauses = kubernetesClientException.getStatus().getDetails().getCauses();

		if (!CollectionUtils.isEmpty(statusCauses)) {
			for (StatusCause statusCause : statusCauses) {
				if (fieldName.equals(statusCause.getField())) {
					return kubernetesClientException.getStatus().getMessage();
				}
			}
		}

		return null;
	}

	private void setImagePullSecret(ScheduleRequest scheduleRequest, CronJob cronJob) {
		String imagePullSecret = KubernetesSchedulerPropertyResolver.getImagePullSecret(scheduleRequest,
				this.kubernetesSchedulerProperties);

		if (StringUtils.hasText(imagePullSecret)) {
			LocalObjectReference localObjectReference = new LocalObjectReference();
			localObjectReference.setName(imagePullSecret);

			cronJob.getSpec().getJobTemplate().getSpec().getTemplate().getSpec().getImagePullSecrets()
					.add(localObjectReference);
		}
	}

	/**
	 * Volume deployment properties are specified in YAML format:
	 *
	 * <code>
	 *     spring.cloud.scheduler.kubernetes.volumes=[{name: testhostpath, hostPath: { path: '/test/override/hostPath' }},
	 *     	{name: 'testpvc', persistentVolumeClaim: { claimName: 'testClaim', readOnly: 'true' }},
	 *     	{name: 'testnfs', nfs: { server: '10.0.0.1:111', path: '/test/nfs' }}]
	 * </code>
	 *
	 * Volumes can be specified as scheduler properties as well as app deployment properties.
	 * Deployment properties override scheduler properties.
	 *
	 * @param request
	 * @return the configured volumes
	 */
	protected List<Volume> getVolumes(ScheduleRequest request) {
		List<Volume> volumes = new ArrayList<>();

		String volumeDeploymentProperty = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.volumes", "");
		if (!StringUtils.isEmpty(volumeDeploymentProperty)) {
			YamlConfigurationFactory<KubernetesSchedulerProperties> volumeYamlConfigurationFactory =
					new YamlConfigurationFactory<>(KubernetesSchedulerProperties.class);
			volumeYamlConfigurationFactory.setYaml("{ volumes: " + volumeDeploymentProperty + " }");
			try {
				volumeYamlConfigurationFactory.afterPropertiesSet();
				volumes.addAll(
						volumeYamlConfigurationFactory.getObject().getVolumes());
			}
			catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid volume '%s'", volumeDeploymentProperty), e);
			}
		}
		// only add volumes that have not already been added, based on the volume's name
		// i.e. allow provided deployment volumes to override scheduler defined volumes
		volumes.addAll(this.kubernetesSchedulerProperties.getVolumes().stream()
				.filter(volume -> volumes.stream()
						.noneMatch(existingVolume -> existingVolume.getName().equals(volume.getName())))
				.collect(Collectors.toList()));

		return volumes;
	}
}
