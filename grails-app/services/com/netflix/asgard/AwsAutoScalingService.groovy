/*
 * Copyright 2012 Netflix, Inc.
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
package com.netflix.asgard

import java.util.List;

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.Activity
import com.amazonaws.services.autoscaling.model.Alarm
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.BlockDeviceMapping
import com.amazonaws.services.autoscaling.model.CreateAutoScalingGroupRequest
import com.amazonaws.services.autoscaling.model.DeleteAutoScalingGroupRequest
import com.amazonaws.services.autoscaling.model.DeleteLaunchConfigurationRequest
import com.amazonaws.services.autoscaling.model.DeletePolicyRequest
import com.amazonaws.services.autoscaling.model.DeleteScheduledActionRequest
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsRequest
import com.amazonaws.services.autoscaling.model.DescribeLaunchConfigurationsResult
import com.amazonaws.services.autoscaling.model.DescribePoliciesRequest
import com.amazonaws.services.autoscaling.model.DescribePoliciesResult
import com.amazonaws.services.autoscaling.model.DescribeScalingActivitiesRequest
import com.amazonaws.services.autoscaling.model.DescribeScalingActivitiesResult
import com.amazonaws.services.autoscaling.model.DescribeScheduledActionsRequest
import com.amazonaws.services.autoscaling.model.DescribeScheduledActionsResult
import com.amazonaws.services.autoscaling.model.Ebs
import com.amazonaws.services.autoscaling.model.Instance
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.amazonaws.services.autoscaling.model.LifecycleState
import com.amazonaws.services.autoscaling.model.PutScalingPolicyRequest
import com.amazonaws.services.autoscaling.model.PutScalingPolicyResult
import com.amazonaws.services.autoscaling.model.PutScheduledUpdateGroupActionRequest
import com.amazonaws.services.autoscaling.model.ResumeProcessesRequest
import com.amazonaws.services.autoscaling.model.ScalingPolicy
import com.amazonaws.services.autoscaling.model.ScheduledUpdateGroupAction
import com.amazonaws.services.autoscaling.model.SuspendProcessesRequest
import com.amazonaws.services.autoscaling.model.TagDescription
import com.amazonaws.services.autoscaling.model.TerminateInstanceInAutoScalingGroupRequest
import com.amazonaws.services.autoscaling.model.UpdateAutoScalingGroupRequest
import com.amazonaws.services.cloudwatch.model.MetricAlarm
import com.amazonaws.services.ec2.model.AvailabilityZone
import com.amazonaws.services.ec2.model.Image
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.SecurityGroup
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.google.common.collect.ImmutableSet
import com.netflix.asgard.cache.CacheInitializer
import com.netflix.asgard.model.AlarmData
import com.netflix.asgard.model.ApplicationInstance
import com.netflix.asgard.model.AutoScalingGroupBeanOptions
import com.netflix.asgard.model.AutoScalingGroupData
import com.netflix.asgard.model.AutoScalingProcessType
import com.netflix.asgard.model.EurekaStatus
import com.netflix.asgard.model.InstanceHealth
import com.netflix.asgard.model.InstanceTypeData
import com.netflix.asgard.model.LaunchConfigurationBeanOptions
import com.netflix.asgard.model.ScalingPolicyData
import com.netflix.asgard.model.SimpleDbSequenceLocator
import com.netflix.asgard.model.StackAsg
import com.netflix.asgard.model.Subnets
import com.netflix.asgard.push.AsgDeletionMode
import com.netflix.asgard.push.Cluster
import com.netflix.asgard.retriever.AwsResultsRetriever
import com.netflix.frigga.ami.AppVersion
import groovyx.gpars.GParsExecutorsPool
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONObject
import org.joda.time.DateTime
import org.joda.time.Duration
import org.springframework.beans.factory.InitializingBean

class AwsAutoScalingService implements CacheInitializer, InitializingBean {

    static transactional = false

    private static final ImmutableSet<String> ignoredScheduledActionProperties = ImmutableSet.of('startTime', 'time',
            'autoScalingGroupName', 'scheduledActionName', 'scheduledActionARN')

    def grailsApplication  // injected after construction
    def applicationService
    def awsClientService
    def awsCloudWatchService
    def awsEc2Service
    def awsLoadBalancerService
    Caches caches
    def cloudReadyService
    def configService
    def discoveryService
    def idService
    def launchTemplateService
    def mergedInstanceService
    def pushService
    def taskService
    def restClientRightScaleService
    def spotInstanceRequestService
    ThreadScheduler threadScheduler

    final AwsResultsRetriever scalingPolicyRetriever = new AwsResultsRetriever<ScalingPolicy, DescribePoliciesRequest,
            DescribePoliciesResult>() {
        protected DescribePoliciesResult makeRequest(Region region, DescribePoliciesRequest request) {
            awsClient.by(region).describePolicies(request)
        }
        protected List<ScalingPolicy> accessResult(DescribePoliciesResult result) {
            result.scalingPolicies
        }
    }

    final AwsResultsRetriever scheduledActionRetriever = new AwsResultsRetriever<ScheduledUpdateGroupAction,
            DescribeScheduledActionsRequest, DescribeScheduledActionsResult>() {
        protected DescribeScheduledActionsResult makeRequest(Region region, DescribeScheduledActionsRequest request) {
            awsClient.by(region).describeScheduledActions(request)
        }
        protected List<ScheduledUpdateGroupAction> accessResult(DescribeScheduledActionsResult result) {
            result.scheduledUpdateGroupActions
        }
    }

    MultiRegionAwsClient<AmazonAutoScaling> awsClient

    void afterPropertiesSet() {
        awsClient = new MultiRegionAwsClient<AmazonAutoScaling>({ Region region ->
            AmazonAutoScaling client = awsClientService.create(AmazonAutoScaling)
            client.setEndpoint("autoscaling.${region}.amazonaws.com")
            client
        })
    }

    void initializeCaches() {
        // Cluster cache has no timer. It gets triggered by the Auto Scaling Group cache callback closure.
        caches.allClusters.ensureSetUp(
                { Region region -> buildClusters(region, caches.allAutoScalingGroups.by(region).list()) }, { },
                { Region region ->
                    boolean awaitingLoadBalancers = caches.allLoadBalancers.by(region).isDoingFirstFill()
                    boolean awaitingAppInstances = caches.allApplicationInstances.by(region).isDoingFirstFill()
                    boolean awaitingImages = caches.allImages.by(region).isDoingFirstFill()
                    boolean awaitingEc2Instances = caches.allInstances.by(region).isDoingFirstFill()
                    !awaitingLoadBalancers && !awaitingAppInstances && !awaitingImages && !awaitingEc2Instances
                }
        )
        caches.allAutoScalingGroups.ensureSetUp({ Region region -> retrieveAutoScalingGroups(region) },
                { Region region -> caches.allClusters.by(region).fill() })
        caches.allLaunchConfigurations.ensureSetUp({ Region region -> retrieveLaunchConfigurations(region) })
        caches.allScalingPolicies.ensureSetUp({ Region region -> retrieveScalingPolicies(region) })
        caches.allTerminationPolicyTypes.ensureSetUp({ Region region -> retrieveTerminationPolicyTypes() })
        caches.allScheduledActions.ensureSetUp({ Region region -> retrieveScheduledActions(region) })
        caches.allSignificantStackInstanceHealthChecks.ensureSetUp(
                { Region region -> retrieveInstanceHealthChecks(region) }, { },
                { Region region ->
                    caches.allApplicationInstances.by(region).filled && caches.allAutoScalingGroups.by(region).filled
                }
        )
    }

    // Clusters

    private Collection<Cluster> buildClusters(Region region, Collection<AutoScalingGroup> allGroups) {

        UserContext userContext = UserContext.auto(region)
        Map<String, Cluster> clusterNamesToClusters = [:]
        allGroups.each { AutoScalingGroup group ->
            // If the name contains a version number then remove the version number to get the cluster name
            String clusterName = Relationships.clusterFromGroupName(group.autoScalingGroupName)

            // Make a cluster object only if we haven't made one yet that matches this ASG.
            if (!clusterNamesToClusters[clusterName]) {
                Cluster cluster = buildCluster(userContext, allGroups, clusterName, From.CACHE)
                if (cluster) {
                    clusterNamesToClusters[clusterName] = cluster
                }
            }
        }

        clusterNamesToClusters.values() as List
    }

    Collection<Cluster> getClusters(UserContext userContext) {
        caches.allClusters.by(userContext.region).list()
    }

    /**
     * Finds all the auto scaling groups that are part of a named cluster.
     *
     * Example:
     *
     * Cluster name: helloworld-example
     * Members: helloworld-example, helloworld-example-v000, helloworld-example-v001
     *
     * @param name the name of the cluster or the name of one of the auto scaling groups in the cluster
     * @return Cluster of ordered auto scaling groups
     */
    Cluster getCluster(UserContext userContext, String name, From from = From.AWS) {
        if (!name) { return null }
        if (from == From.CACHE) {
            return caches.allClusters.by(userContext.region).get(name)
        }
        // If the name contains a version number then remove the version number to get the cluster name
        String clusterName = Relationships.clusterFromGroupName(name)
        if (!clusterName) { return null }
        Collection<AutoScalingGroup> allGroupsSharedCache = getAutoScalingGroups(userContext)
        Cluster cluster = buildCluster(userContext, allGroupsSharedCache, clusterName)
        caches.allClusters.by(userContext.region).put(clusterName, cluster)
        cluster
    }

    AutoScalingGroupData buildAutoScalingGroupData(UserContext userContext, AutoScalingGroup group) {
        List<String> instanceIds = group.instances.collect { it.instanceId }
        List<MergedInstance> mergedInstances = mergedInstanceService.getMergedInstancesByIds(userContext, instanceIds)
        Map<String, Collection<LoadBalancerDescription>> instanceIdsToLoadBalancerLists =
                awsLoadBalancerService.mapInstanceIdsToLoadBalancers(userContext, instanceIds)
        Map<String, Image> imageIdsToImages = awsEc2Service.mapImageIdsToImagesForMergedInstances(userContext,
                mergedInstances)
        Collection<ScalingPolicyData> scalingPolicies = getScalingPolicyDatas(userContext, group.autoScalingGroupName)
        AutoScalingGroupData.from(group, instanceIdsToLoadBalancerLists, mergedInstances, imageIdsToImages, scalingPolicies)
    }

    Cluster buildCluster(UserContext userContext, Collection<AutoScalingGroup> allGroups, String clusterName,
            From loadAutoScalingGroupsFrom = From.AWS) {

        // Optimization: find the candidate ASGs that start with the cluster name to avoid dissecting every group name.
        List<AutoScalingGroup> candidates = allGroups.findAll { it.autoScalingGroupName.startsWith(clusterName) }

        // Later the ASG CachedMap should contain AutoScalingGroupData objects instead of AutoScalingGroup objects.

        // Find ASGs whose names equal the cluster name or the cluster name followed by a push number.
        List<AutoScalingGroup> groups = candidates.findAll {
            Relationships.clusterFromGroupName(it.autoScalingGroupName) == clusterName }

        Set<String> asgNamesForCacheRefresh = groups*.autoScalingGroupName as Set
        // If a separate Asgard instance recently created an ASG then it wouldn't have been found in the cache.
        asgNamesForCacheRefresh << clusterName

        // Reduce the ASG list to the ASGs that still exist in Amazon. As a happy side effect, update the ASG cache.
        groups = getAutoScalingGroups(userContext, asgNamesForCacheRefresh, loadAutoScalingGroupsFrom)

        if (groups.size()) {
            // This looks similar to buildAutoScalingGroupData() but it's faster to prepare the instance, ELB and AMI
            // lists once for the entire cluster than multiple times.
            List<String> instanceIds = groups.collect { it.instances }.collect { it.instanceId }.flatten()
            Map<String, Collection<LoadBalancerDescription>> instanceIdsToLoadBalancerLists =
                        awsLoadBalancerService.mapInstanceIdsToLoadBalancers(userContext, instanceIds)
            List<MergedInstance> mergedInstances = mergedInstanceService.getMergedInstancesByIds(userContext,
                        instanceIds)
            Map<String, Image> imageIdsToImages = awsEc2Service.mapImageIdsToImagesForMergedInstances(userContext,
                    mergedInstances)
            List<AutoScalingGroupData> clusterGroups = groups.collect { AutoScalingGroup asg ->
                AutoScalingGroupData.from(asg, instanceIdsToLoadBalancerLists, mergedInstances, imageIdsToImages, [])
            }

            return new Cluster(clusterGroups)
        }
        null
    }

    // Auto Scaling Groups
			
	private String getRightScaleServerArrayId(JSONArray links) {
		getIdFromRelLinks(links, 'self')
	}
	
	private getRightScaleServerArrayFirstInstanceId(JSONArray links) {
		getIdFromRelLinks(links, 'next_instance')
	}
			
	private getIdFromRelLinks(JSONArray links, String relName) {
		def link = links.find { it.rel == relName }
		if (link == null) {
			return 'unavailable'
		}
		String href = link.href
		href.substring(href.lastIndexOf('/') + 1)
	}

	private List<AutoScalingGroup> retrieveAllRightScaleArrays(Collection<String> names = null) {
        JSONArray jsonArrays = restClientRightScaleService.getAsJson(
            'https://us-4.rightscale.com/api/server_arrays?view=instance_detail');
        
		List<AutoScalingGroup> groups = []
		jsonArrays.each {
            if (getIdFromRelLinks(it.links, 'deployment') != configService.getRightScaleDeploymentId()) {
                // only work with arrays in current deployment (dev vs. test vs. prod in RightScale)
                return
            }
                
			if (names != null) {
				if (!names.find { name -> name == it.name }) {
					return
				}
			}
			log.debug "array = " + it
			String arrayId = getRightScaleServerArrayId(it.links)
			String nextInstanceId = getRightScaleServerArrayFirstInstanceId(it.links) ?: 'unknown'
			TagDescription tag1 = new TagDescription(
				key : 'rightscale_serverarrayid',
				value : arrayId
			)
			TagDescription tag2 = new TagDescription(
				key : 'rightscale_next_instance_id',
				value : nextInstanceId
			)
			com.amazonaws.services.ec2.model.Instance nextInstance = awsEc2Service.getRightScaleInstance(nextInstanceId)
			TagDescription tag3 = new TagDescription(
				key : 'rightscale_next_instance_image_id',
				value : nextInstance.imageId
			)
			TagDescription tag4 = new TagDescription(
				key : 'rightscale_instance_type_id',
				value : nextInstance.instanceType
			)

			List<JSONObject> nonZeroDCs = it.datacenter_policy.findAll { policy -> policy.weight != '0.0' && policy.weight != '0' }
			List<String> datacenters = nonZeroDCs*.datacenter_href;
			
			List<AvailabilityZone> azs = awsEc2Service.getAvailabilityZones(Region.US_SOUTH_1)
			List<String> azNames = []
			
			datacenters.each { dc->
				def String datacenterId = dc.substring(dc.lastIndexOf('/') + 1)
				AvailabilityZone az = azs.find { az ->
					def messages = az.messages
					boolean match = messages.find { message ->
						message.message == datacenterId
					}.any()
					if (match) {
						azNames.add(az.zoneName)
					}
				}
				log.debug 'az = ' + az
			}
			
			log.debug 'azNames = ' + azNames
			
			def AutoScalingGroup group = new AutoScalingGroup(
				autoScalingGroupName : it.name,
				minSize : it.elasticity_params.bounds.min_count.toInteger(),
				maxSize : it.elasticity_params.bounds.max_count.toInteger(),
				launchConfigurationName : 'fakeconfigname',
				tags : [tag1, tag2, tag3, tag4],
				availabilityZones: azNames
			)
			
			JSONArray jsonInstances = restClientRightScaleService.getAsJson('https://us-4.rightscale.com/api/server_arrays/' + arrayId + '/current_instances?view=extended')
			List<Instance> instances = []
			jsonInstances.each {
				String instanceId = getIdFromRelLinks(it.links, 'self')
				// this will make the link to the instance not work (as it's different in RightScale), but will allow Eureka to work
				instanceId = it.private_ip_addresses[0] ?: ''
				String datacenterId = getIdFromRelLinks(it.links, 'datacenter')
				AvailabilityZone datecenter = azs.find { it.messages[0].message == datacenterId }
				Instance instance = new Instance(
					instanceId : instanceId,
					availabilityZone: datecenter.zoneName,
					healthStatus: 'sick', // TODO - get real instance data
					launchConfigurationName : 'fakeconfigname'
					//lifeCycleState
					)
				instances.add(instance)
			}
			group.setInstances(instances)
			groups.add(group)
		}
		log.debug 'groups = ' + groups
		return groups
	}

	private List<AutoScalingGroup> retrieveAllRightScaleArraysByNames(Collection<String> names) {
		// TODO:  look for a more efficient query to rightscale
		List<AutoScalingGroup> allGroups = retrieveAllRightScaleArrays(names);
		allGroups
	}

    private List<AutoScalingGroup> retrieveAutoScalingGroups(Region region) {
		if (region.code == Region.US_SOUTH_1_REGION_CODE) {
			return retrieveAllRightScaleArrays();
		}
        List<AutoScalingGroup> groups = []
        DescribeAutoScalingGroupsResult result = retrieveAutoScalingGroups(region, null)
        while (true) {
            groups.addAll(result.getAutoScalingGroups())
            if (result.getNextToken() == null) {
                break
            }
            result = retrieveAutoScalingGroups(region, result.getNextToken())
        }
		log.debug 'groups for ' + region.code + ' = ' + groups
		//[{
		//AutoScalingGroupName: acmeair_auth_service_tc7,
		//AutoScalingGroupARN: arn:aws:autoscaling:us-east-1:665469383253:autoScalingGroup:5c2670b0-ef31-4d7d-9a24-ff9ec4ab0dc5:autoScalingGroupName/acmeair_auth_service_tc7,
		//LaunchConfigurationName: acmeair_auth_service_tc7-20130805211228
		//MinSize: 0
		//MaxSize: 10
		//DesiredCapacity: 0
		//DefaultCooldown: 10
		//AvailabilityZones: [us-east-1a],
		//LoadBalancerNames: [],
		//HealthCheckType: EC2,
		//HealthCheckGracePeriod: 600,
		//Instances: [],
		//CreatedTime: Mon Aug 05 17:12:29 EDT 2013,
		//SuspendedProcesses: [],
		//VPCZoneIdentifier: ,
		//EnabledMetrics: [],
		//Tags: [],
		//TerminationPolicies: [Default],
		//},
		//{AutoScalingGroupName: acmeair_auth_service_wlp-v002,
		//AutoScalingGroupARN: ..
        groups
    }

    private DescribeAutoScalingGroupsResult retrieveAutoScalingGroups(Region region, String nextToken) {
        awsClient.by(region).describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withNextToken(nextToken))
    }

    Collection<AutoScalingGroup> getAutoScalingGroups(UserContext userContext) {
        caches.allAutoScalingGroups.by(userContext.region).list()
    }

    List<AutoScalingGroup> getAutoScalingGroupsForApp(UserContext userContext, String appName) {
        getAutoScalingGroups(userContext).findAll {
            appName.toLowerCase() == Relationships.appNameFromGroupName(it.autoScalingGroupName)
        }
    }

    private Collection<InstanceHealth> retrieveInstanceHealthChecks(Region region) {
        Collection<AutoScalingGroup> asgs = getAutoScalingGroupsInStacks(region, configService.significantStacks)
        Collection<String> instanceIds = asgs*.instances*.instanceId.flatten()
        Collection<ApplicationInstance> instances = caches.allApplicationInstances.by(region).list().
                findAll { it.instanceId in instanceIds }
        GParsExecutorsPool.withExistingPool(threadScheduler.scheduler) {
            instances.collectParallel {
                new InstanceHealth(it.instanceId, awsEc2Service.checkHostHealth(it.healthCheckUrl))
            }
        } as Collection<InstanceHealth>
    }

    public Collection<AutoScalingGroup> getAutoScalingGroupsInStacks(Region region, Collection<String> stacks) {
        caches.allAutoScalingGroups.by(region).list().findAll {
            Relationships.stackNameFromGroupName(it.autoScalingGroupName) in stacks
        }
    }

    /**
     * Constructs StackAsgs corresponding to ASGs that belong to a Stack (based on naming convention).
     *
     * @param userContext who made the call, why, and in what region
     * @param stackName to retrieve ASGs for
     * @return details about the ASGs that are part of the Stack
     */
    List<StackAsg> getStack(UserContext userContext, String stackName) {
        Collection<AutoScalingGroup> stackAsgs = getAutoScalingGroupsInStacks(userContext.region, [stackName])
        stackAsgs.collect { stackAsg ->
            Collection<String> healthyInstances = stackAsg.instances*.instanceId.findAll {
                caches.allSignificantStackInstanceHealthChecks.by(userContext.region).get(it)?.isHealthy
            }
            LaunchConfiguration launchConfig = getLaunchConfiguration(userContext, stackAsg.launchConfigurationName,
                    From.CACHE)
            Image image = awsEc2Service.getImage(userContext, launchConfig.imageId, From.CACHE)
            AppVersion appVersion = Relationships.dissectAppVersion(image?.appVersion)
            new StackAsg(stackAsg, launchConfig, appVersion, healthyInstances.size())
        }
    }

    AutoScalingGroup getAutoScalingGroup(UserContext userContext, String name, From from = From.AWS) {
        if (!name) { return null }
        List<AutoScalingGroup> groups = getAutoScalingGroups(userContext, [name], from)
        Check.loneOrNone(groups, AutoScalingGroup)
    }

    List<AutoScalingGroup> getAutoScalingGroups(UserContext userContext, Collection<String> names,
                                                From from = From.AWS) {
        if (names) {
            if (from == From.CACHE) {
                return names.collect { caches.allAutoScalingGroups.by(userContext.region).get(it) }.findAll { it != null }
            }
			List<AutoScalingGroup> groups
			if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
				groups = retrieveAllRightScaleArraysByNames(names)
				log.debug groups
			}
			else {
				DescribeAutoScalingGroupsResult result = awsClient.by(userContext.region).describeAutoScalingGroups(
                    new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(names))
				groups = result.getAutoScalingGroups()
			}
            if (from == From.AWS) {
                // Update the ASG cache for all the requested ASG names including the ASGs that no longer exist.
                for (String name in names) {
                    if (name) {
                        // To remove non-existent ASGs from the cache, put the null group reference.
                        AutoScalingGroup group = groups.find { it.autoScalingGroupName == name }
                        caches.allAutoScalingGroups.by(userContext.region).put(name, group)
                    }
                }
                return groups.collect { it.copy() }
            } else if (from == From.AWS_NOCACHE) {
                return groups
            }
        }
        []
    }

    AutoScalingGroup getAutoScalingGroupFor(UserContext userContext, String instanceId) {
        if (!instanceId) { return null }
        def groups = getAutoScalingGroups(userContext)
        //println "Looking for group for instance " + instanceId
        groups.find { it.instances.any { it.instanceId == instanceId } }
    }

    List<AutoScalingGroup> getAutoScalingGroupsForLB(UserContext userContext, String lbName) {
        def groups = getAutoScalingGroups(userContext)
        //println "Looking for group for LB " + lbName
        groups.findAll { it.loadBalancerNames.any { it == lbName } }
    }

    AutoScalingGroup getAutoScalingGroupForLaunchConfig(UserContext userContext, String lcName) {
        def groups = getAutoScalingGroups(userContext)
        //println "Looking for group for " + lcName
        groups.find { it.launchConfigurationName == lcName }
    }

    /**
     * @return List of all auto scaling termination policy types
     */
    List<String> getTerminationPolicyTypes() {
        caches.allTerminationPolicyTypes.list().sort()
    }

    private List<String> retrieveTerminationPolicyTypes() {
        awsClient.by(Region.defaultRegion()).describeTerminationPolicyTypes().terminationPolicyTypes
    }

    private static final Integer MAX_RECORDS_PER_REQUEST = 50
    static final Integer UNLIMITED = Integer.MAX_VALUE

    List<Activity> getAutoScalingGroupActivities(UserContext userContext, String name, Integer maxTotalActivities) {
		if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
			return []
		}
        List<Activity> activities = []
        Integer remainingActivitiesToFetch = maxTotalActivities
        String nextToken = null

        while (remainingActivitiesToFetch > 0) {
            Integer maxRecordsForNextRequest = Math.min(MAX_RECORDS_PER_REQUEST, remainingActivitiesToFetch)
            DescribeScalingActivitiesRequest request = new DescribeScalingActivitiesRequest()
            request.withAutoScalingGroupName(name).withMaxRecords(maxRecordsForNextRequest).withNextToken(nextToken)
            try {
                AmazonAutoScaling client = awsClient.by(userContext.region)
                DescribeScalingActivitiesResult result = client.describeScalingActivities(request)
                activities.addAll(result.activities)
                nextToken = result.nextToken
            } catch (AmazonServiceException ignored) {
                // The ASG stopped existing. This race condition happens during automated polling in a unit test.
                break
            }
            if (nextToken == null) {
                // We're done collecting activities
                break
            }
            sleep 20 // Avoid rate limiting before using next token
            remainingActivitiesToFetch = maxTotalActivities - activities.size()
        }
        activities
    }

    /**
     * Checks whether the auto scaling group is currently set up for automated dynamic scaling or not.
     *
     * @param userContext who, where, why
     * @param group the auto scaling group to analyze
     * @return true if the group's desired size should be set manually because alarms are not causing dynamic scaling
     */
    boolean shouldGroupBeManuallySized(UserContext userContext, AutoScalingGroup group) {
        boolean alarmNotificationsSuspended = group?.isProcessSuspended(AutoScalingProcessType.AlarmNotifications)
        String name = group.autoScalingGroupName
        boolean dynamicScalingDriversExist = getScalingPoliciesForGroup(userContext, name) ||
                getScheduledActionsForGroup(userContext, name)
        !dynamicScalingDriversExist || alarmNotificationsSuspended
    }

    /**
     * Finds scaling policy specified by name.
     *
     * @param name of the scaling policy to retrieve
     * @return scaling policy details for name, null if no name was specified
     */
    ScalingPolicy getScalingPolicy(UserContext userContext, String policyName) {
        if (!policyName) { return null }
        Check.loneOrNone(getScalingPolicies(userContext, [policyName]), ScalingPolicy)
    }

    /**
     * Finds all scaling policies specified by name.
     *
     * @param names of the scaling policies to retrieve
     * @return scaling policy details for names, empty if no names were specified
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#describePolicies(DescribePoliciesRequest)
     */
    List<ScalingPolicy> getScalingPolicies(UserContext userContext, Collection<String> policyNames) {
        if (!policyNames) { return [] }
        final DescribePoliciesRequest request = new DescribePoliciesRequest(policyNames: policyNames)
        awsClient.by(userContext.region).describePolicies(request).scalingPolicies
    }

    private List<ScalingPolicy> retrieveScalingPolicies(Region region) {
		if (region.code == Region.US_SOUTH_1_REGION_CODE) return []
        scalingPolicyRetriever.retrieve(region, new DescribePoliciesRequest())
    }

    /**
     * Finds all scaling policies for a region.
     *
     * @return scaling policy action details
     */
    Collection<ScalingPolicy> getAllScalingPolicies(UserContext userContext) {
        caches.allScalingPolicies.by(userContext.region).list()
    }

    /**
     * Finds all scaling policies for an ASG.
     *
     * @param name of the ASG
     * @return scaling policy details, empty if no names were specified
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#describePolicies(DescribePoliciesRequest)
     */
    List<ScalingPolicy> getScalingPoliciesForGroup(UserContext userContext, String autoScalingGroupName) {
		if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
			return []
		}
        if (!autoScalingGroupName) { return [] }
        DescribePoliciesRequest request = new DescribePoliciesRequest(autoScalingGroupName: autoScalingGroupName)
        awsClient.by(userContext.region).describePolicies(request).scalingPolicies
    }

    /**
     * Copy scheduled actions for use in another ASG (like the next ASG in a Cluster).
     *
     * @param userContext who made the call, why, and in what region
     * @param newAsgName name of the new ASG
     * @param sourceScheduledActions original scheduled actions
     * @return copies of the original scheduled actions with an updated ASG name
     */
    List<ScheduledUpdateGroupAction> copyScheduledActionsForNewAsg(UserContext userContext, String newAsgName,
            List<ScheduledUpdateGroupAction> sourceScheduledActions) {
        sourceScheduledActions.collect {
            ScheduledUpdateGroupAction newScheduledAction = BeanState.ofSourceBean(it).
                    ignoreProperties(ignoredScheduledActionProperties).injectState(new ScheduledUpdateGroupAction())
            newScheduledAction.with {
                autoScalingGroupName = newAsgName
                scheduledActionName = Relationships.buildScalingPolicyName(newAsgName, nextPolicyId(userContext))
            }
            newScheduledAction
        }
    }

    List<ScalingPolicyData> getScalingPolicyDatas(UserContext userContext, String autoScalingGroupName) {
        if (!autoScalingGroupName) { return [] }
        final List<ScalingPolicy> scalingPolicies = getScalingPoliciesForGroup(userContext, autoScalingGroupName)
        Map<ScalingPolicy, Collection<MetricAlarm>> scalingPolicyToAlarms = [:]
        final Collection<Alarm> alarmReferences = scalingPolicies*.alarms.flatten() as Collection<Alarm>
        final Collection<MetricAlarm> alarms = awsCloudWatchService.getAlarms(userContext, alarmReferences*.alarmName)
        final Map<String, ScalingPolicy> alarmNameToScalingPolicy = [:]
        scalingPolicies.each { ScalingPolicy scalingPolicy ->
            scalingPolicy.alarms.each { Alarm alarm ->
                alarmNameToScalingPolicy[alarm.alarmName] = scalingPolicy
            }
        }
        scalingPolicyToAlarms = alarms.groupBy {
            alarmNameToScalingPolicy[it.alarmName]
        } as Map
        final List<ScalingPolicyData> scalingPolicyDatas = []
        scalingPolicies.each { ScalingPolicy scalingPolicy ->
            final Collection<MetricAlarm> alarmsPerPolicy = scalingPolicyToAlarms[scalingPolicy]
            scalingPolicyDatas << ScalingPolicyData.fromPolicyAndAlarms(scalingPolicy, alarmsPerPolicy)
        }
        scalingPolicyDatas
    }

    /**
     * Creates scaling policies based on details. Will update existing ones.
     *
     * @param names of the scaling policies to retrieve
     * @return updated scaling policy names
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#putScalingPolicy(PutScalingPolicyRequest)
     */
    List<String> createScalingPolicies(UserContext userContext, Collection<ScalingPolicyData> scalingPolicies,
                               Task existingTask = null) {
        List<String> scalingPolicyNames = []
        if (!scalingPolicies) {
            return scalingPolicyNames
        }
        Integer policyCount = scalingPolicies.size()
        String msg = "Create ${policyCount} Scaling Polic${policyCount == 1 ? 'y' : 'ies'}"
        taskService.runTask(userContext, msg, { Task task ->
            scalingPolicies.eachWithIndex { ScalingPolicyData scalingPolicyData, int policyIndex ->
                PutScalingPolicyRequest request = scalingPolicyData.toPutScalingPolicyRequest(nextPolicyId(userContext))
                scalingPolicyNames << request.policyName
                if (policyIndex >= 1) { Time.sleepCancellably(configService.cloudThrottle) }
                task.log("Create Scaling Policy '${request.policyName}'")
                final PutScalingPolicyResult result = awsClient.by(userContext.region).putScalingPolicy(request)
                scalingPolicyData.alarms.eachWithIndex { AlarmData alarm, int alarmIndex ->
                    if (alarmIndex >= 1) { Time.sleepCancellably(configService.cloudThrottle) }
                    awsCloudWatchService.createAlarm(userContext, alarm, result.policyARN, existingTask)
                }
            }
        }, null, existingTask)
        scalingPolicyNames
    }

    /**
     * Updates a scaling policy based on details. Will try to create one if it does not exist.
     *
     * @param scaling policy details to update with
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#putScalingPolicy(PutScalingPolicyRequest)
     */
    void updateScalingPolicy(UserContext userContext, ScalingPolicyData policy, Task existingTask = null) {
        taskService.runTask(userContext, "Update Scaling Policy '${policy.policyName}'", { Task task ->
            awsClient.by(userContext.region).putScalingPolicy(policy.toPutScalingPolicyRequest())
        }, Link.to(EntityType.scalingPolicy, policy.policyName), existingTask)
    }

    /**
     * Deletes a scaling policy.
     *
     * @param scalingPolicy details for scaling policy to delete
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#deletePolicy(DeletePolicyRequest)
     */
    void deleteScalingPolicy(UserContext userContext, ScalingPolicy scalingPolicy, Task existingTask = null) {
        // TODO - this method needs to calculate only alarms that will be orphaned, and delete them in a single call
        taskService.runTask(userContext, "Delete Scaling Policy '${scalingPolicy.policyName}'", { Task task ->
            final deletePolicyRequest = new DeletePolicyRequest(policyName: scalingPolicy.policyName,
                    autoScalingGroupName: scalingPolicy.autoScalingGroupName)
            awsClient.by(userContext.region).deletePolicy(deletePolicyRequest)
            awsCloudWatchService.deleteAlarms(userContext, scalingPolicy.alarms*.alarmName, task)
        }, Link.to(EntityType.autoScaling, scalingPolicy.autoScalingGroupName), existingTask)
        getScalingPolicy(userContext, scalingPolicy.policyName)
    }

    /**
     * Finds scheduled action specified by name.
     *
     * @param name of the scheduled action to retrieve
     * @return scheduled action details for name, null if no name was specified
     */
    ScheduledUpdateGroupAction getScheduledAction(UserContext userContext, String name) {
        if (!name) { return null }
        Check.loneOrNone(getScheduledActions(userContext, [name]), ScheduledUpdateGroupAction)
    }

    /**
     * Finds all scheduled actions specified by name.
     *
     * @param names of the scheduled actions to retrieve
     * @return scheduled action details for names, empty if no names were specified
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#describeScheduledActions(DescribeScheduledActionsRequest)
     */
    List<ScheduledUpdateGroupAction> getScheduledActions(UserContext userContext, Collection<String> names) {
        if (!names) { return [] }
        DescribeScheduledActionsRequest request = new DescribeScheduledActionsRequest(scheduledActionNames: names)
        awsClient.by(userContext.region).describeScheduledActions(request).scheduledUpdateGroupActions
    }

    /**
     * Finds all scheduled actions for an ASG.
     *
     * @param name of the ASG
     * @return scheduled action details, empty if no names were specified
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#describeScheduledActions(DescribeScheduledActionsRequest)
     */
    List<ScheduledUpdateGroupAction> getScheduledActionsForGroup(UserContext userContext, String autoScalingGroupName) {
		if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
			return []
		}
        if (!autoScalingGroupName) { return [] }
        def request = new DescribeScheduledActionsRequest(autoScalingGroupName: autoScalingGroupName)
        awsClient.by(userContext.region).describeScheduledActions(request)?.scheduledUpdateGroupActions
    }

    private List<ScheduledUpdateGroupAction> retrieveScheduledActions(Region region) {
		if (region.code == Region.US_SOUTH_1_REGION_CODE) return []
        scheduledActionRetriever.retrieve(region, new DescribeScheduledActionsRequest())
    }

    /**
     * Finds all scheduled actions for a region.
     *
     * @return scheduled action details
     */
    Collection<ScheduledUpdateGroupAction> getAllScheduledActions(UserContext userContext) {
        caches.allScheduledActions.by(userContext.region).list()
    }

    /**
     * Creates scheduled actions based on details. Will update existing ones.
     *
     * @param names of the scheduled actions to retrieve
     * @return updated scheduled action names
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#putScheduledUpdateGroupAction(PutScheduledUpdateGroupActionRequest)
     */
    List<String> createScheduledActions(UserContext userContext, Collection<ScheduledUpdateGroupAction> actions,
                                       Task existingTask = null) {
        List<String> actionNames = []
        if (!actions) {
            return actionNames
        }
        Integer count = actions.size()
        String msg = "Create ${count} Scheduled Action${count == 1 ? '' : 's'}"
        taskService.runTask(userContext, msg, { Task task ->
            actions.eachWithIndex { ScheduledUpdateGroupAction action, int index ->
                PutScheduledUpdateGroupActionRequest request = BeanState.ofSourceBean(action).
                        ignoreProperties(['startTime', 'time']).injectState(new PutScheduledUpdateGroupActionRequest())
                actionNames << action.scheduledActionName
                if (index >= 1) { Time.sleepCancellably(configService.cloudThrottle) }
                task.log("Create Scheduled Action '${action.autoScalingGroupName}'")
                awsClient.by(userContext.region).putScheduledUpdateGroupAction(request)
            }
        }, null, existingTask)
        actionNames
    }

    /**
     * Updates a scheduled action based on details. Will try to create one if it does not exist.
     *
     * @param scheduled action details to update with
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#putScheduledUpdateGroupAction(PutScheduledUpdateGroupActionRequest)
     */
    void updateScheduledAction(UserContext userContext, ScheduledUpdateGroupAction action, Task existingTask = null) {
        def request = new PutScheduledUpdateGroupActionRequest(scheduledActionName: action.scheduledActionName,
                autoScalingGroupName: action.autoScalingGroupName, minSize: action.minSize, maxSize: action.maxSize,
                desiredCapacity: action.desiredCapacity, recurrence: action.recurrence)
        taskService.runTask(userContext, "Update Scheduled Action '${action.scheduledActionName}'", { Task task ->
            awsClient.by(userContext.region).putScheduledUpdateGroupAction(request)
        }, Link.to(EntityType.scheduledAction, action.scheduledActionName), existingTask)
    }

    /**
     * Deletes a scheduled action.
     *
     * @param action details for action to delete
     * @see com.amazonaws.services.autoscaling.AmazonAutoScaling#deleteScheduledAction(DeleteScheduledActionRequest)
     */
    void deleteScheduledAction(UserContext userContext, ScheduledUpdateGroupAction action, Task existingTask = null) {
        taskService.runTask(userContext, "Delete Scheduled Action '${action.scheduledActionName}'", { Task task ->
            def request = new DeleteScheduledActionRequest(scheduledActionName: action.scheduledActionName,
                    autoScalingGroupName: action.autoScalingGroupName)
            awsClient.by(userContext.region).deleteScheduledAction(request)
        }, Link.to(EntityType.autoScaling, action.autoScalingGroupName), existingTask)
        getScheduledAction(userContext, action.scheduledActionName)
    }

    String nextPolicyId(UserContext userContext) {
        idService.nextId(userContext, SimpleDbSequenceLocator.Policy)
    }

    /**
     * Create an ASG.
     *
     * @param userContext who made the call, why, and in what region
     * @param groupTemplate ASG attributes for the new ASG
     * @param launchConfigName for new ASG
     * @param suspendedProcesses to suspend on the new ASG
     * @param existingTask
     * @return created ASG
     */
    AutoScalingGroup createAutoScalingGroup(UserContext userContext, AutoScalingGroupBeanOptions groupTemplate,
            Task existingTask = null) {
        String name = groupTemplate.autoScalingGroupName

        taskService.runTask(userContext, "Create Autoscaling Group '${name}'", { Task task ->
            Subnets subnets = awsEc2Service.getSubnets(userContext)
            CreateAutoScalingGroupRequest request = groupTemplate.getCreateAutoScalingGroupRequest(subnets)
            awsClient.by(userContext.region).createAutoScalingGroup(request)
            groupTemplate.suspendedProcesses.each {
                suspendProcess(userContext, it, name, task)
            }
        }, Link.to(EntityType.autoScaling, name), existingTask)
        getAutoScalingGroup(userContext, name)
    }

	AutoScalingGroup createAutoScalingGroupRightScale(UserContext userContext, AutoScalingGroup groupTemplate,
		LaunchConfiguration launchConfigTemplate, Collection<AutoScalingProcessType> suspendedProcesses,
		Task existingTask = null) {
		String name = groupTemplate.autoScalingGroupName
	
		taskService.runTask(userContext, "Create Autoscaling Group '${name}'", { Task task ->
			Image image = caches.allImages.by(Region.US_SOUTH_1).get(launchConfigTemplate.imageId)
			String imageHref = image.tags.find { it.key = 'rightscale_imagehref'}.value
			String deploymentId = configService.getRightScaleDeploymentId()
			String templateId = configService.getRightScaleServerTemplateId()
			String arrayName = groupTemplate.autoScalingGroupName
			String arrayDesc = groupTemplate.autoScalingGroupName + ' auto generated description'
			String cloudId = configService.getRightScaleCloudId()
			def allInstanceTypes = caches.allInstanceTypes.by(Region.US_SOUTH_1).list()
			InstanceTypeData instanceType = allInstanceTypes?.find{ it.hardwareProfile.instanceType == launchConfigTemplate.instanceType }
			String instanceTypeName = instanceType?.rightscaleInstanceTypeId
			String instanceTypeHref = '/api/clouds/' + cloudId + '/instance_types/' + instanceTypeName
			
			List<List<String>> params = [
				['server_array[name]', arrayName],
				['server_array[description]', arrayDesc],
				['server_array[deployment_href]', '/api/deployments/' + deploymentId],
				['server_array[array_type]', 'alert'],
				['server_array[state]', 'enabled'],
				['server_array[instance][server_template_href]', '/api/server_templates/' + templateId],
				['server_array[instance][cloud_href]', '/api/clouds/' + cloudId],
				['server_array[instance][image_href]', imageHref],
				['server_array[instance][instance_type_href]', instanceTypeHref],
				['server_array[instance][multi_cloud_image_href]', configService.getRightScaleMultiCloudImageRestHref()],
				['server_array[elasticity_params][alert_specific_params][decision_threshold]', '51'],
				['server_array[elasticity_params][bounds][min_count]', groupTemplate.minSize.toString()],
				['server_array[elasticity_params][bounds][max_count]', groupTemplate.maxSize.toString()],
				['server_array[elasticity_params][pacing][resize_calm_time]', '5'],
				['server_array[elasticity_params][pacing][resize_down_by]', '1'],
				['server_array[elasticity_params][pacing][resize_up_by]', '1']
			]
			def List<List<String>> dcPolicy = getRightScaleDataCenterPolicy(groupTemplate.availabilityZones)
			
			def resp2 = restClientRightScaleService.post('https://us-4.rightscale.com/api/server_arrays', params + dcPolicy)
			log.debug resp2
			suspendedProcesses.each {
				suspendProcess(userContext, it, name, task)
			}
		}, Link.to(EntityType.autoScaling, name), existingTask)
		getAutoScalingGroup(userContext, name)
	}
		
	List<List<String>> getRightScaleDataCenterPolicy(List<String> selectedAZs) {
		def List<AvailabilityZone> allAzs = awsEc2Service.getAvailabilityZones(Region.US_SOUTH_1)
		def List<List<String>> allDcPolicies = []
		def String weight = 100 / selectedAZs.size()
		def String lastweight = weight + 100 % selectedAZs.size()
		selectedAZs.eachWithIndex { az, index ->
			def fullAz = allAzs.find { it.zoneName == az }
			def String datacenterId = fullAz.messages[0].message
			def w = (allAzs.size == index - 1) ? lastWeight : weight
			def List<List<String>> dcPolicy = [
				['server_array[datacenter_policy][][datacenter_href]',
					'/api/clouds/' + configService.getRightScaleCloudId() + '/datacenters/' + datacenterId],
				['server_array[datacenter_policy][][max]', '0'],
				['server_array[datacenter_policy][][weight]', w]
			]
			allDcPolicies = allDcPolicies + dcPolicy
		}
		log.debug 'allDcPolicies = ' + allDcPolicies 
		allDcPolicies
	}
	
    /**
     * Resize an ASG.
     *
     * @param userContext who made the call, why, and in what region
     * @param asgName name of the ASG
     * @param minSize value to update ASG with
     * @param desiredCapacity value to update ASG with
     * @param maxSize value to update ASG with
     * @return updated ASG
     */
    AutoScalingGroupBeanOptions resizeAutoScalingGroup(UserContext userContext, String asgName, Integer minSize,
            Integer desiredCapacity, Integer maxSize) {
        Check.notNull(asgName, String)
        updateAutoScalingGroup(userContext, asgName) { AutoScalingGroupBeanOptions asg ->
            if (minSize != null) { asg.minSize = minSize }
            if (maxSize != null) { asg.maxSize = maxSize }
            if (desiredCapacity != null) { asg.desiredCapacity = desiredCapacity }
        }
    }

    /**
     * Analyze an ASG and determine if it is operational.
     *
     * @param userContext who made the call, why, and in what region
     * @param asgName name of the ASG
     * @param expectedInstanceCount number of instances that are expected
     * @return textual description of the reason the ASG is not operational, or an empty String if it is
     */
    String reasonAsgIsNotOperational(UserContext userContext, String asgName, int expectedInstanceCount) {
        if (expectedInstanceCount == 0) {
            return ''
        }
        AutoScalingGroup asg = getAutoScalingGroup(userContext, asgName)
        if (!asg) {
            throw new IllegalStateException("ASG '${asgName}' does not exist.")
        }
        if (asg.instances.size() < expectedInstanceCount) {
            return "Instance count is ${asg.instances.size()}. Waiting for ${expectedInstanceCount}."
        }
        if (asg.instances.find { it.lifecycleState != LifecycleState.InService.name() }) {
            return 'Waiting for instances to be in service.'
        }
        if (configService.getRegionalDiscoveryServer(userContext.region)) {
            List<ApplicationInstance> applicationInstances = discoveryService.getAppInstancesByIds(userContext,
                    asg.instances*.instanceId)
            if (applicationInstances.size() < expectedInstanceCount) {
                return 'Waiting for Eureka data about instances.'
            }
            if (applicationInstances.find { it.status != EurekaStatus.UP.name() }) {
                return 'Waiting for all instances to be available in Eureka.'
            }
            if (!awsEc2Service.checkHostsHealth(applicationInstances*.healthCheckUrl)) {
                return 'Waiting for all instances to pass health checks.'
            }
        }
        if (asg.loadBalancerNames) {
            String loadBalancerThatSeesOutOfServiceInstance = asg.loadBalancerNames.find {
                if (asg.loadBalancerNames.size() > 1) { Time.sleepCancellably(250) }
                awsLoadBalancerService.getInstanceStateDatas(userContext, it, [asg]).find {
                    it.autoScalingGroupName == asg.autoScalingGroupName && it.state != LifecycleState.InService.name()
                }
            }
            if (loadBalancerThatSeesOutOfServiceInstance) {
                return 'Waiting for all instances to pass ELB health checks.'
            }
        }
        ''
    }

    /**
     * Update an existing ASG.
     *
     * @param userContext who made the call, why, and in what region
     * @param asgName name of the ASG
     * @param transform changes from the old ASG to the updated version
     * @return the state of the updated ASG
     */
    AutoScalingGroupBeanOptions updateAutoScalingGroup(UserContext userContext, String asgName,
            Closure transform) {
        AutoScalingGroup originalAsg = getAutoScalingGroup(userContext, asgName)
        if (!originalAsg) { return null }
        Subnets subnets = awsEc2Service.getSubnets(userContext)
        AutoScalingGroupBeanOptions originalAsgOptions = AutoScalingGroupBeanOptions.from(originalAsg, subnets)
        AutoScalingGroupBeanOptions newAsgOptions = AutoScalingGroupBeanOptions.from(originalAsgOptions)
        transform(newAsgOptions)
        newAsgOptions.autoScalingGroupName = asgName
        SuspendProcessesRequest suspendProcessesRequest = originalAsgOptions.
                getSuspendProcessesRequestForUpdate(newAsgOptions.suspendedProcesses)
        if (suspendProcessesRequest) {
            awsClient.by(userContext.region).suspendProcesses(suspendProcessesRequest)
        }
        ResumeProcessesRequest resumeProcessesRequest = originalAsgOptions.
                getResumeProcessesRequestForUpdate(newAsgOptions.suspendedProcesses)
        if (resumeProcessesRequest) {
            awsClient.by(userContext.region).resumeProcesses(resumeProcessesRequest)
        }
        awsClient.by(userContext.region).updateAutoScalingGroup(newAsgOptions.getUpdateAutoScalingGroupRequest(subnets))
        newAsgOptions
    }

    AutoScalingGroup updateAutoScalingGroup(UserContext userContext, AutoScalingGroupData autoScalingGroupData,
            Collection<AutoScalingProcessType> suspendProcessTypes = [],
            Collection<AutoScalingProcessType> resumeProcessTypes = [], existingTask = null) {
        [
                'autoScalingGroupName',
                'launchConfigurationName',
                'minSize',
                'desiredCapacity',
                'maxSize',
                'defaultCooldown',
                'healthCheckType',
                'healthCheckGracePeriod',
                'availabilityZones',
        ].each {
            Check.notNull(autoScalingGroupData."${it}", AutoScalingGroup, it)
        }
        AutoScalingGroup group = getAutoScalingGroup(userContext, autoScalingGroupData.autoScalingGroupName)

        Set<AutoScalingProcessType> processTypesToSuspend = suspendProcessTypes -
                group.suspendedProcessTypes
        processTypesToSuspend.retainAll(AutoScalingProcessType.getUpdatableProcesses())

        Set<AutoScalingProcessType> enabledProcessTypes = AutoScalingProcessType.getUpdatableProcesses() -
                group.suspendedProcessTypes
        final Set<AutoScalingProcessType> processTypesToResume = resumeProcessTypes -
                enabledProcessTypes
        processTypesToResume.retainAll(AutoScalingProcessType.getUpdatableProcesses())

        UpdateAutoScalingGroupRequest request = BeanState.ofSourceBean(autoScalingGroupData).injectState(
                new UpdateAutoScalingGroupRequest()).withHealthCheckType(autoScalingGroupData.healthCheckType.name())

        Subnets subnets = awsEc2Service.getSubnets(userContext)
        String vpcZoneIdentifier = subnets.constructNewVpcZoneIdentifierForZones(group.VPCZoneIdentifier,
                autoScalingGroupData.availabilityZones)
        request.withVPCZoneIdentifier(vpcZoneIdentifier)
        if (!autoScalingGroupData.availabilityZones) {
            // No zones were selected because there was no chance to change them. Keep the old zones.
            request.availabilityZones = group.availabilityZones
        }

        taskService.runTask(userContext, "Update Autoscaling Group '${autoScalingGroupData.autoScalingGroupName}'", { Task task ->
			if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
				log.debug 'tags = ' + autoScalingGroupData.tags
				String serverid = autoScalingGroupData.tags.find { tag -> tag.key = 'rightscale_serverarrayid' }.value
				log.debug 'serverid = ' + serverid
				boolean launchAndTerminateShouldBeDisabled = suspendProcessTypes.contains(AutoScalingProcessType.Launch) && suspendProcessTypes.contains(AutoScalingProcessType.Terminate)
				
				List<List<String>> dcPolicy = getRightScaleDataCenterPolicy(autoScalingGroupData.availabilityZones)
				
				List<List<String>> params = [
					['server_array[elasticity_params][bounds][min_count]' , autoScalingGroupData.minSize.toString()],
					['server_array[elasticity_params][bounds][max_count]' , autoScalingGroupData.maxSize.toString()],
					['server_array[state]', launchAndTerminateShouldBeDisabled ? 'disabled' : 'enabled']
				] 

				def resp2 = restClientRightScaleService.put('https://us-4.rightscale.com/api/server_arrays/' + serverid, params + dcPolicy)
				log.debug resp2
			}
			else {
				processTypesToSuspend.each {
					suspendProcess(userContext, it, autoScalingGroupData.autoScalingGroupName, task)
				}
				processTypesToResume.each {
					resumeProcess(userContext, it, autoScalingGroupData.autoScalingGroupName, task)
				}
				awsClient.by(userContext.region).updateAutoScalingGroup(request)
			}

        }, Link.to(EntityType.autoScaling, autoScalingGroupData.autoScalingGroupName), existingTask)

        // Refresh auto scaling group and cluster cache
        group = getAutoScalingGroup(userContext, autoScalingGroupData.autoScalingGroupName)
        getCluster(userContext, autoScalingGroupData.autoScalingGroupName)
        group
    }

    AutoScalingGroup suspendProcess(UserContext userContext, AutoScalingProcessType autoScalingProcessType,
                                    String autoScalingGroupName, Task existingTask) {
        SuspendProcessesRequest suspendProcessesRequest = new SuspendProcessesRequest().withAutoScalingGroupName(autoScalingGroupName).
                withScalingProcesses([autoScalingProcessType.name()])
        taskService.runTask(userContext,
                "${autoScalingProcessType.suspendMessage} for auto scaling group '${autoScalingGroupName}'", { task ->
            awsClient.by(userContext.region).suspendProcesses(suspendProcessesRequest)
        }, Link.to(EntityType.autoScaling, autoScalingGroupName), existingTask)
        getAutoScalingGroup(userContext, autoScalingGroupName) // refreshing cache
    }

    AutoScalingGroup resumeProcess(UserContext userContext, AutoScalingProcessType autoScalingProcessType,
                                   String autoScalingGroupName, Task existingTask) {
        ResumeProcessesRequest resumeProcessesRequest = new ResumeProcessesRequest().withAutoScalingGroupName(autoScalingGroupName).
                withScalingProcesses([autoScalingProcessType.name()])
        taskService.runTask(userContext,
                "${autoScalingProcessType.resumeMessage} for auto scaling group '${autoScalingGroupName}'", { task ->
            awsClient.by(userContext.region).resumeProcesses(resumeProcessesRequest)
        }, Link.to(EntityType.autoScaling, autoScalingGroupName), existingTask)
        getAutoScalingGroup(userContext, autoScalingGroupName) // refreshing cache
    }

    void setExpirationTime(UserContext userContext, String autoScalingGroupName, DateTime expirationTime,
                           Task existingTask = null) {
        Map<String, String> tagNameValuePairs = [(TagNames.EXPIRATION_TIME): Time.format(expirationTime)]
        createOrUpdateAutoScalingGroupTags(userContext, autoScalingGroupName, tagNameValuePairs, existingTask)
    }

    void postponeExpirationTime(UserContext userContext, String autoScalingGroupName, Duration extraTime,
                           Task existingTask = null) {
        AutoScalingGroup group = getAutoScalingGroup(userContext, autoScalingGroupName)
        AutoScalingGroupData autoScalingGroupData = buildAutoScalingGroupData(userContext, group)
        DateTime expirationTime = autoScalingGroupData.expirationTimeAsDateTime()
        if (expirationTime) {
            DateTime now = Time.now()
            DateTime timeToStartFrom = now.isAfter(expirationTime) ? now : expirationTime
            DateTime newExpirationTime = timeToStartFrom.withDurationAdded(extraTime, 1)
            Duration newDuration = new Duration(Time.now(), newExpirationTime)
            if (newDuration < AutoScalingGroupData.MAX_EXPIRATION_DURATION) {
                setExpirationTime(userContext, autoScalingGroupName, newExpirationTime, existingTask)
            }
        }
    }

    void removeExpirationTime(UserContext userContext, String autoScalingGroupName, Task existingTask = null) {
        deleteAutoScalingGroupTags(userContext, autoScalingGroupName, [TagNames.EXPIRATION_TIME], existingTask)
    }

    void createOrUpdateAutoScalingGroupTags(UserContext userContext, String autoScalingGroupName, Map<String,
                                    String> tagNameValuePairs, Task existingTask = null) {

        // TODO: Re-enable this call after Amazon fixes bugs on their side and tell us it's safe again

        /*

        // Hopefully Amazon will eventually change CreateOrUpdateTagsRequest to take List<Tag> instead of List<String>
        List<String> tagStringsEqualDelimited = tagNameValuePairs.collect { "${it.key}=${it.value}".toString() }

        String suffix = tagNameValuePairs.size() == 1 ? '' : 's'
        String msg = "Create tag${suffix} ${tagNameValuePairs} on Auto Scaling Group on '${autoScalingGroupName}'"
        taskService.runTask(userContext, msg, { Task task ->
            CreateOrUpdateTagsRequest request = new CreateOrUpdateTagsRequest(autoScalingGroupName: autoScalingGroupName,
                    forceOverwriteTags: true, propagate: true, tags: tagStringsEqualDelimited)
            awsClient.by(userContext.region).createOrUpdateTags(request)
        }, Link.to(EntityType.autoScaling, autoScalingGroupName), existingTask)

        */
    }

    void deleteAutoScalingGroupTags(UserContext userContext, String autoScalingGroupName, List<String> tagNames,
                                   Task existingTask = null) {

        // TODO: Re-enable this call after Amazon fixes bugs on their side and tell us it's safe again

        /*

        String suffix = tagNames.size() == 1 ? '' : 's'
        String msg = "Delete tag${suffix} ${tagNames} on Auto Scaling Group on '${autoScalingGroupName}'"
        taskService.runTask(userContext, msg, { Task task ->
            DeleteTagsRequest request = new DeleteTagsRequest(autoScalingGroupName: autoScalingGroupName,
                    tagsToDelete: tagNames)
            awsClient.by(userContext.region).deleteTags(request)
        }, Link.to(EntityType.autoScaling, autoScalingGroupName), existingTask)

        */
    }

    void deleteAutoScalingGroup(UserContext userContext, String name, AsgDeletionMode mode = AsgDeletionMode.ATTEMPT,
                                Task existingTask = null) {
        Check.notNull(name, AutoScalingGroup, "name")
        taskService.runTask(userContext, "Delete Auto Scaling Group '${name}'", { task ->
            AutoScalingGroup group = getAutoScalingGroup(userContext, name)
            if (group) {
                def request = new DeleteAutoScalingGroupRequest().withAutoScalingGroupName(name)
                if (mode == AsgDeletionMode.FORCE) { request.withForceDelete(true) }
                awsClient.by(userContext.region).deleteAutoScalingGroup(request)
                getAutoScalingGroup(userContext, name) // Update cache with current ASG state. Force delete is slow.
            }
            // Also refresh the cluster which may or may not still exist with other auto scaling groups.
            getCluster(userContext, name)
        }, Link.to(EntityType.autoScaling, name), existingTask)
    }

    void deregisterAllInstancesInAutoScalingGroupFromLoadBalancers(UserContext userContext, String name,
                                                                   Task existingTask = null) {
        Check.notNull(name, AutoScalingGroup, "name")
        taskService.runTask(userContext, "Deregister all instances in Auto Scaling Group '${name}' from ELBs", { Task task ->
            AutoScalingGroup group = getAutoScalingGroup(userContext, name)
            if (group) {
                List<String> loadBalancerNames = group.loadBalancerNames
                List<String> instanceIds = group.instances*.instanceId
                for (String loadBalancerName in loadBalancerNames) {
                    awsLoadBalancerService.removeInstances(userContext, loadBalancerName, instanceIds, task)
                }
            }
        }, Link.to(EntityType.autoScaling, name), existingTask)
    }

    void deregisterInstancesInAutoScalingGroupFromLoadBalancers(UserContext userContext, String groupName,
            Collection<String> instanceIds, Task existingTask = null) {
        Check.notNull(groupName, AutoScalingGroup, 'groupName')
        AutoScalingGroup group = getAutoScalingGroup(userContext, groupName)
        Closure work = { Task task ->
            List<Instance> instances = instanceIds.collect { new Instance().withInstanceId(it) } // elasticloadbalancing.model.Instance type
            if (instances) {
                for (String loadBalancerName in group.loadBalancerNames) {
                    awsLoadBalancerService.removeInstances(userContext, loadBalancerName, instanceIds, task)
                }
            }
        }
        String msg = "Remove instances ${instanceIds} from load balancers '${group.loadBalancerNames}'"
        taskService.runTask(userContext, msg, work, Link.to(EntityType.autoScaling, groupName), existingTask)
        getAutoScalingGroup(userContext, groupName)
    }

    /**
     * Terminates the specified instance if an auto scaling group can be found that contains that instance.
     *
     * @param userContext who made the call, why, and in what region
     * @param instanceId the id of the instance to terminate
     * @return AutoScalingGroup the group in its state prior to shrinkage, or null if not found
     */
    AutoScalingGroup terminateInstanceAndDecrementAutoScalingGroup(UserContext userContext, String instanceId) {
        Check.notNull(instanceId, Instance, 'instanceId')
        AutoScalingGroup group = getAutoScalingGroupFor(userContext, instanceId)
        if (group) {
            if (group.desiredCapacity <= group.minSize) {
                final AutoScalingGroupData autoScalingGroupData = AutoScalingGroupData.forUpdate(
                        group.autoScalingGroupName, group.launchConfigurationName,
                        group.minSize - 1, group.desiredCapacity, group.maxSize, group.defaultCooldown,
                        group.healthCheckType, group.healthCheckGracePeriod, group.terminationPolicies,
                        group.availabilityZones)
                updateAutoScalingGroup(userContext, autoScalingGroupData)
            }
            String msg = "Terminate instance '${instanceId}' and shrink auto scaling group '${group.autoScalingGroupName}'"
            taskService.runTask(userContext, msg, { Task task ->
                deregisterInstancesInAutoScalingGroupFromLoadBalancers(userContext, group.autoScalingGroupName,
                    [instanceId], task)
                awsClient.by(userContext.region).terminateInstanceInAutoScalingGroup(
                        new TerminateInstanceInAutoScalingGroupRequest().withInstanceId(instanceId).
                                withShouldDecrementDesiredCapacity(true))
            }, Link.to(EntityType.autoScaling, group.autoScalingGroupName))

            return group
        }
        null
    }

    // public TerminateInstanceInAutoScalingGroupResponse terminateInstanceInAutoScalingGroup(TerminateInstanceInAutoScalingGroupRequest request) throws AmazonAutoScalingException;

    // Launch Configurations

    List<LaunchConfiguration> retrieveLaunchConfigurations(Region region) {
        List<LaunchConfiguration> configs = []
		if (region.code == Region.US_SOUTH_1_REGION_CODE) {
			LaunchConfiguration config = new LaunchConfiguration(
				launchConfigurationName : "fakeconfigname",
				launchConfigurationARN : "fakearn",
				imageId : "fakeimage",
				keyName : "fakekeyname",
				securityGroups : ["fakesecuritygroup"],
			).withUserData('fakeuserdata'.encodeAsBase64())
			configs.add(config);
			configs.each { ensureUserDataIsDecodedAndTruncated(it) }
			return configs;
		}
        DescribeLaunchConfigurationsResult result = retrieveLaunchConfigurationsForToken(region, null)
        while (true) {
            configs.addAll(result.getLaunchConfigurations())
            if (result.getNextToken() == null) {
                break
            }
            result = retrieveLaunchConfigurationsForToken(region, result.getNextToken())
        }
        configs.each { ensureUserDataIsDecodedAndTruncated(it) }
		//configs.each { log.debug "LaunchConfiguration = " + it }
		// LaunchConfiguration = {LaunchConfigurationName: acmeairwebappwlp-v002-20130807025337,
		//LaunchConfigurationARN: arn:aws:autoscaling:us-east-1:665469383253:launchConfiguration:85ab917f-4e17-4d7d-80be-13647c782986:launchConfigurationName/acmeairwebappwlp-v002-20130807025337,
		//ImageId: ami-fe3d7a97, KeyName: acmeair-netflix, SecurityGroups: [acmeair-netflix], UserData: export CLOUD_ENVIRONMENT=prod
        configs
    }

    private DescribeLaunchConfigurationsResult retrieveLaunchConfigurationsForToken(Region region, String nextToken) {
        awsClient.by(region).describeLaunchConfigurations(new DescribeLaunchConfigurationsRequest().withNextToken(nextToken))
    }

    private void ensureUserDataIsDecodedAndTruncated(LaunchConfiguration launchConfiguration) {
        ensureUserDataIsDecoded(launchConfiguration)
        String userData = launchConfiguration.userData
        int maxLength = configService.cachedUserDataMaxLength
        if (userData.length() > maxLength) {
            launchConfiguration.userData = userData.substring(0, maxLength)
        }
    }

    private void ensureUserDataIsDecoded(LaunchConfiguration launchConfiguration) {
        launchConfiguration.userData = Ensure.decoded(launchConfiguration.userData)
    }

    Collection<LaunchConfiguration> getLaunchConfigurations(UserContext userContext) {
        caches.allLaunchConfigurations.by(userContext.region).list()
    }

    LaunchConfiguration getLaunchConfiguration(UserContext userContext, String name, From from = From.AWS) {
        if (!name) { return null }
        if (from == From.CACHE) {
            return caches.allLaunchConfigurations.by(userContext.region).get(name)
        }
		List<LaunchConfiguration> launchConfigs = []
		if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
			LaunchConfiguration config = new LaunchConfiguration(
				launchConfigurationName : "fakeconfigname",
				launchConfigurationARN : "fakearn",
				imageId : "fakeimage",
				keyName : "fakekeyname",
				securityGroups : ["fakesecuritygroup"],
			).withUserData('fakeuserdata'.encodeAsBase64())
			launchConfigs.add(config);
		}
		else {
			def result = awsClient.by(userContext.region).describeLaunchConfigurations(
				new DescribeLaunchConfigurationsRequest().withLaunchConfigurationNames([name]))
			launchConfigs = result.getLaunchConfigurations()
		}
        if (launchConfigs.size() > 0) {
            LaunchConfiguration launchConfig = Check.lone(launchConfigs, LaunchConfiguration)
            ensureUserDataIsDecoded(launchConfig)
            return caches.allLaunchConfigurations.by(userContext.region).put(name, launchConfig)
        }
        null
    }

    Collection<String> getLaunchConfigurationNamesForAutoScalingGroup(UserContext userContext,
                                                                      String autoScalingGroupName) {
        Set<String> launchConfigNamesForGroup = [] as Set
        String currentLaunchConfigName = getAutoScalingGroup(userContext, autoScalingGroupName).launchConfigurationName
        if (currentLaunchConfigName) {
            launchConfigNamesForGroup.add(currentLaunchConfigName)
        }
        def pat = ~/^${autoScalingGroupName}-\d{12,}$/
        launchConfigNamesForGroup.addAll(getLaunchConfigurations(userContext).findAll {
                    it.launchConfigurationName ==~ pat }.collect { it.launchConfigurationName })
        new ArrayList<String>(launchConfigNamesForGroup)
    }

    List<LaunchConfiguration> getLaunchConfigurationsForApp(UserContext userContext, String appName) {
        def pat = ~/^(launch-)?${appName.toLowerCase()}(_\d+)?-\d+$/
        getLaunchConfigurations(userContext).findAll { it.launchConfigurationName ==~ pat }
    }

    /**
     * Finds all the launch configurations that reference a specified security group.
     *
     * @param userContext who, where, why
     * @param group the security group for which to find associated launch configurations
     * @return the list of relevant launch configurations
     */
    List<LaunchConfiguration> getLaunchConfigurationsForSecurityGroup(UserContext userContext, SecurityGroup group) {
        Collection<LaunchConfiguration> allLaunchConfigs = getLaunchConfigurations(userContext)
        allLaunchConfigs.findAll { group.groupId in it.securityGroups || group.groupName in it.securityGroups }
    }

    Collection<LaunchConfiguration> getLaunchConfigurationsUsingImageId(UserContext userContext, String imageId) {
        Check.notEmpty(imageId)
        getLaunchConfigurations(userContext).findAll { LaunchConfiguration launchConfiguration ->
            launchConfiguration.imageId == imageId
        }
    }

    // mutators

    CreateAutoScalingGroupResult createLaunchConfigAndAutoScalingGroup(UserContext userContext,
            AutoScalingGroup groupTemplate, LaunchConfiguration launchConfigTemplate,
            Collection<AutoScalingProcessType> suspendedProcesses, boolean enableChaosMonkey = false,
            Task existingTask = null) {

        CreateAutoScalingGroupResult result = new CreateAutoScalingGroupResult()
        String groupName = groupTemplate.autoScalingGroupName
        String launchConfigName = Relationships.buildLaunchConfigurationName(groupName)
        String msg = "Create Auto Scaling Group '${groupName}'"
        Subnets subnets = awsEc2Service.getSubnets(userContext)

        AutoScalingGroupBeanOptions groupOptions = AutoScalingGroupBeanOptions.from(groupTemplate, subnets)
        groupOptions.launchConfigurationName = launchConfigName
        groupOptions.suspendedProcesses = suspendedProcesses

        LaunchConfigurationBeanOptions launchConfig = LaunchConfigurationBeanOptions.from(launchConfigTemplate)
        launchConfig.launchConfigurationName = launchConfigName
        launchConfig.securityGroups = launchTemplateService.includeDefaultSecurityGroups(
                launchConfigTemplate.securityGroups, groupTemplate.VPCZoneIdentifier as boolean, userContext.region)
        taskService.runTask(userContext, msg, { Task task ->
            String userData = launchTemplateService.buildUserData(userContext, groupOptions, launchConfig)
            launchConfig.userData = userData
            result.launchConfigName = launchConfigName
            result.autoScalingGroupName = groupName

        if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
            result.launchConfigCreated = true // liar liar pants on fire, but not needed until we support multiple instance types	
        }
        else {
            try {
                createLaunchConfiguration(userContext, launchConfig, task)
                result.launchConfigCreated = true
            } catch (AmazonServiceException launchConfigCreateException) {
                result.launchConfigCreateException = launchConfigCreateException
            }
        }

        try {
            if (userContext.region.code == Region.US_SOUTH_1_REGION_CODE) {
                createAutoScalingGroupRightScale(userContext, groupTemplate, launchConfigTemplate, suspendedProcesses, task)
            }
            else {
                createAutoScalingGroup(userContext, groupOptions, task)
            }
            result.autoScalingGroupCreated = true
        } catch (AmazonServiceException autoScalingCreateException) {
                result.autoScalingCreateException = autoScalingCreateException
                try {
                    deleteLaunchConfiguration(userContext, launchConfigName, task)
                    result.launchConfigDeleted = true
                }
                catch (AmazonServiceException launchConfigDeleteException) {
                    result.launchConfigDeleteException = launchConfigDeleteException
                }
            }

            if (result.autoScalingGroupCreated && enableChaosMonkey) {
                String cluster = Relationships.clusterFromGroupName(groupTemplate.autoScalingGroupName)
                task.log("Enabling Chaos Monkey for ${cluster}.")
                Region region = userContext.region
                result.cloudReadyUnavailable = !cloudReadyService.enableChaosMonkeyForCluster(region, cluster)
            }
        }, Link.to(EntityType.autoScaling, groupName), existingTask)

        result
    }

    /**
     * Create launch configuration.
     *
     * @param userContext who made the call, why, and in what region
     * @param launchConfiguration to create
     * @param existingTask to contain work, if any
     */
    void createLaunchConfiguration(UserContext userContext, LaunchConfigurationBeanOptions launchConfiguration,
            Task existingTask = null) {
        String name = launchConfiguration.launchConfigurationName
        String imageId = launchConfiguration.imageId
        Check.notNull(name, LaunchConfiguration, "name")
        Check.notNull(imageId, LaunchConfiguration, "imageId")
        Check.notNull(launchConfiguration.keyName, LaunchConfiguration, "keyName")
        Check.notNull(launchConfiguration.instanceType, LaunchConfiguration, "instanceType")
        taskService.runTask(userContext, "Create Launch Configuration '${name}' with image '${imageId}'", { Task task ->
            List<BlockDeviceMapping> blockDeviceMappings = []
            if (configService.instanceTypeNeedsEbsVolumes(launchConfiguration.instanceType)) {
                List<String> deviceNames = configService.ebsVolumeDeviceNamesForLaunchConfigs
                for (deviceName in deviceNames) {
                    blockDeviceMappings << new BlockDeviceMapping(
                            deviceName: deviceName,
                            ebs: new Ebs(volumeSize: configService.sizeOfEbsVolumesAddedToLaunchConfigs))
                }
            }
            launchConfiguration.blockDeviceMappings = blockDeviceMappings
            awsClient.by(userContext.region).createLaunchConfiguration(launchConfiguration.
                    getCreateLaunchConfigurationRequest(userContext, spotInstanceRequestService))
            pushService.addAccountsForImage(userContext, imageId, task)

        }, Link.to(EntityType.launchConfiguration, name), existingTask)
        getLaunchConfiguration(userContext, name)
    }

    def deleteLaunchConfiguration(UserContext userContext, String name, Task existingTask = null) {
        Check.notNull(name, LaunchConfiguration, "name")
        taskService.runTask(userContext, "Delete Launch Configuration '${name}'", { task ->
            def request = new DeleteLaunchConfigurationRequest().withLaunchConfigurationName(name)
            awsClient.by(userContext.region).deleteLaunchConfiguration(request)
        }, Link.to(EntityType.launchConfiguration, name), existingTask)
        caches.allLaunchConfigurations.by(userContext.region).remove(name)
    }
}

/**
 * Records the results of trying to create an Auto Scaling Group.
 */
class CreateAutoScalingGroupResult {
    String launchConfigName
    String autoScalingGroupName
    Boolean launchConfigCreated
    AmazonServiceException launchConfigCreateException
    Boolean autoScalingGroupCreated
    AmazonServiceException autoScalingCreateException
    Boolean launchConfigDeleted
    AmazonServiceException launchConfigDeleteException
    Boolean cloudReadyUnavailable // Just a warning, does not affect success.

    String toString() {
        StringBuilder output = new StringBuilder()
        if (launchConfigCreated) {
            output.append("Launch Config '${launchConfigName}' has been created. ")
        }
        if (launchConfigCreateException) {
            output.append("Could not create Launch Config for new Auto Scaling Group: ${launchConfigCreateException}. ")
        }
        if (autoScalingGroupCreated) {
            output.append("Auto Scaling Group '${autoScalingGroupName}' has been created. ")
        }
        if (autoScalingCreateException) {
            output.append("Could not create AutoScaling Group: ${autoScalingCreateException}. ")
        }
        if (launchConfigDeleted) { output.append("Launch Config '$launchConfigName' has been deleted. ") }
        if (launchConfigDeleteException) {
            output.append("Failed to delete Launch Config '${launchConfigName}': ${launchConfigDeleteException}. ")
        }
        if (cloudReadyUnavailable) {
            output.append('Chaos Monkey was not enabled because Cloudready is currently unavailable. ')
        }
        output.toString()
    }

    Boolean succeeded() {
        launchConfigCreated && autoScalingGroupCreated &&
                !launchConfigDeleted && !launchConfigCreateException && !autoScalingCreateException
    }
}
