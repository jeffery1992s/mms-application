package com.java.mc.schedule;

import java.net.URL;
import java.util.List;
import java.util.Properties;

import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

import com.java.mc.bean.DatasourceConfig;
import com.java.mc.bean.Schedule;
import com.java.mc.bean.SendCondition;
import com.java.mc.db.DBOperation;
import com.java.mc.utils.Constants;
import com.java.mc.utils.WebUtils;

@Component
public class ScheduleConfiguration {
	private static final Logger logger = LoggerFactory.getLogger(ScheduleConfiguration.class);

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DBOperation dbOperation;

	private Scheduler scheduler;

	public void init() throws Exception {
		this.scheduler = this.scheduler();
		this.scheduleSync();
		try {
			this.scheduler.start();
		} catch (SchedulerException e) {
			logger.error(e.getMessage(), e);
			System.exit(-1);
		}
		logger.info("Schedule started!");
	}

	/**
	 * schedule sync from the database to the quartz.
	 * 
	 * @throws Exception
	 */
	private void scheduleSync() throws Exception {
		List<Schedule> scheduleList = this.dbOperation.getSyncScheduleList();
		for (Schedule schedule : scheduleList) {
			this.scheduleSync(schedule);
		}
		this.newOrUpdateSchedule();
	}

	/**
	 * add or update a new schedule
	 * 
	 * @throws Exception
	 * 
	 */
	public void newOrUpdateSchedule() throws Exception {
		List<Schedule> scheduleList = this.dbOperation.getInitScheduleList();
		for (Schedule schedule : scheduleList) {
			try {
				this.scheduleSync(schedule);
			} catch (Exception e) {
				logger.info("[action=schedule sync][result=failed][jobname={}][message={}]", schedule.getJobName(),
						e.getMessage());
				continue;
			}
		}
	}

	public void scheduleSync(Schedule schedule) throws Exception {

		// add or update job
		JobDetail jobDetail = this.jobDetail(schedule);
		if (jobDetail != null) {
			this.addorupdateJob(jobDetail);
		}

		// cron
		CronTriggerFactoryBean cronTriggerFactoryBean = new CronTriggerFactoryBean();
		cronTriggerFactoryBean.setJobDetail(jobDetail);
		cronTriggerFactoryBean.setName(schedule.getJobName());
		cronTriggerFactoryBean.setGroup(schedule.getGroupName());
		cronTriggerFactoryBean.setCronExpression(schedule.getCronExpression());
		cronTriggerFactoryBean.setStartDelay(schedule.getDelayTime());
		cronTriggerFactoryBean.setStartTime(schedule.getDelayDate());
		cronTriggerFactoryBean.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
		cronTriggerFactoryBean.afterPropertiesSet();

		Trigger trigger = cronTriggerFactoryBean.getObject();

		if (this.scheduler.checkExists(trigger.getKey())) {
			this.scheduler.rescheduleJob(trigger.getKey(), trigger);
		} else {
			this.scheduler.scheduleJob(trigger);
		}
		// sync the status
		if (!Constants.Y.equals(schedule.getStatus())) {
			this.dbOperation.setScheduleStatus(schedule.getId(), Constants.Y);
		}

		logger.info("[action=schedule sync][result=success][jobname={}]", schedule.getJobName());
	}

	/**
	 * add or update job
	 * 
	 * @param jobDetail
	 * @throws Exception
	 */
	public void addorupdateJob(Schedule schedule) throws Exception {
		JobDetail jobDetail = this.jobDetail(schedule);
		this.addorupdateJob(jobDetail);
	}

	/**
	 * add or update job
	 * 
	 * @param jobDetail
	 * @throws SchedulerException
	 */
	public void addorupdateJob(JobDetail jobDetail) throws SchedulerException {
		if (jobDetail != null) {
			// update or add jobdetail
			this.scheduler.addJob(jobDetail, true);
		}
	}

	public JobDetail jobDetail(Schedule schedule) throws Exception {
		JobDetailFactoryBean jobDetailFactoryBean = new JobDetailFactoryBean();
		jobDetailFactoryBean.setName(schedule.getJobName());
		jobDetailFactoryBean.setGroup(schedule.getGroupName());
		jobDetailFactoryBean.setDurability(true);
		jobDetailFactoryBean.setJobClass(ScheduleJob.class);
		JobDataMap jobDataMap = jobDetailFactoryBean.getJobDataMap();
		jobDataMap.put(Constants.SCHEDULE_ID, schedule.getId());

		// validation for the jobdatamap.
		this.validation(jobDataMap);

		jobDetailFactoryBean.afterPropertiesSet();

		return jobDetailFactoryBean.getObject();
	}

	@Bean
	private Scheduler scheduler() throws Exception {
		SchedulerFactoryBean schedule = new SchedulerFactoryBean();
		schedule.setDataSource(this.jdbcTemplate.getDataSource());
		schedule.setTransactionManager(this.transactionManager);
		schedule.setApplicationContext(this.applicationContext);
		Properties properties = new Properties();
		properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
		schedule.setQuartzProperties(properties);
		
		AutowiringSpringBeanJobFactory jobFactory = new AutowiringSpringBeanJobFactory();
		jobFactory.setApplicationContext(this.applicationContext);

		schedule.setJobFactory(jobFactory);

		schedule.afterPropertiesSet();
		return schedule.getScheduler();
	}

	/**
	 * validate the job data.
	 * 
	 * @param jobDataMap
	 * @throws Exception
	 */
	public void validation(JobDataMap jobDataMap) throws Exception {
		if (!jobDataMap.containsKey(Constants.SCHEDULE_ID)) {
			throw new Exception("未指定计划任务ID");
		}
		Integer scheduleId = jobDataMap.getInt(Constants.SCHEDULE_ID);
		Schedule schedule = this.dbOperation.getScheduleById(scheduleId);
		if (schedule != null) {
			Integer action = schedule.getActionType();
			// mail scan schedule
			if (Constants.ACTION_MAIL_SCAN == action || Constants.ACTION_SM_SCAN == action) {

				if (Constants.ACTION_MAIL_SCAN == action || Constants.ACTION_SM_SCAN == action) {
					schedule.setSendCondition(this.dbOperation.getSendConditionListByScheduleId(scheduleId));
				}

				// check mail server configuration
				if (Constants.ACTION_MAIL_SCAN == action) {
					if (schedule.getSendConditionList() == null || schedule.getSendConditionList().size() <= 0) {
						if (schedule.getMsid() == null) {
							throw new Exception("缺少邮件服务器配置信息！");
						}
					}
					
					if(schedule.getMsid() != null){
						if (this.dbOperation.getMailServerConfigrationById(schedule.getMsid()) == null) {
							throw new Exception("邮件服务器配置信息未找到，或已被删除！");
						}
					}

					if (schedule.getSendConditionList() != null && schedule.getSendConditionList().size() > 0) {
						for (SendCondition sc : schedule.getSendConditionList()) {
							if (this.dbOperation.getMailServerConfigrationById(sc.getHandlerId()) == null) {
								throw new Exception("邮件服务器配置信息未找到，或已被删除！");
							}
						}
					}
				}
				if (Constants.ACTION_SM_SCAN == action) {
					if (schedule.getSendConditionList() == null || schedule.getSendConditionList().size() <= 0) {
						if (schedule.getSmid() == null) {
							throw new Exception("缺少短信通道配置信息！");
						}
					}
					if(schedule.getSmid() != null){
						if (this.dbOperation.getShortMessageConfigrationById(schedule.getSmid()) == null) {
							throw new Exception("短信通道配置信息未找到，或已被删除！");
						}
					}

					if (schedule.getSendConditionList() != null && schedule.getSendConditionList().size() > 0) {
						for (SendCondition sc : schedule.getSendConditionList()) {
							if (this.dbOperation.getShortMessageConfigrationById(sc.getHandlerId()) == null) {
								throw new Exception("短信通道配置信息未找到，或已被删除！");
							}
						}
					}
				}

				// check datasource configuration.
				if (StringUtils.isEmpty(schedule.getDsid())) {
					throw new Exception("缺少数据库配置信息！");
				}
				DatasourceConfig dsc = this.dbOperation.getDSConfigurationById(schedule.getDsid());
				if (dsc == null) {
					throw new Exception("数据源配置信息未找到，或已被删除！");
				}
			}

			// MMS plan
			if (Constants.ACTION_MMS_PLAN == action) {
				if (StringUtils.isEmpty(schedule.getUrl())) {
					throw new Exception("缺少MMS URL信息！");
				}

				// check the url.
				try {
					URL url = new URL(schedule.getUrl());
					String protocol = url.getProtocol();
					String host = url.getHost();
					int port = url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
					URL tryurl = new URL(protocol, host, port, "");
					WebUtils.access(tryurl.toURI());
				} catch (Exception e) {
					throw new Exception("MMS URL无效");
				}
			}

			// MMS window command
			if (Constants.ACTION_WINDOW_COMMAND == action) {
				if (StringUtils.isEmpty(schedule.getCommand())) {
					throw new Exception("未指定运行命令");
				}
			}

			// sql run action
			if (Constants.ACTION_DB_RUNSQL == action) {
				if (StringUtils.isEmpty(schedule.getSqlSentence())) {
					throw new Exception("未指定SQL语句");
				}

				// check datasource configuration.
				if (StringUtils.isEmpty(schedule.getDsid())) {
					throw new Exception("缺少数据库配置信息！");
				}
				DatasourceConfig dsc = this.dbOperation.getDSConfigurationById(schedule.getDsid());
				if (dsc == null) {
					throw new Exception("数据源配置信息未找到，或已被删除！");
				}
			}

			return;
		}
		throw new Exception("不支持的任务类型！");
	}
}
