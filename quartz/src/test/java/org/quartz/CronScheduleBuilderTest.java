/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.quartz;

import static org.quartz.CronScheduleBuilder.atHourAndMinuteOnGivenDaysOfWeek;
import static org.quartz.TriggerBuilder.newTrigger;

import junit.framework.Assert;
import junit.framework.TestCase;
import org.junit.Test;

import java.text.ParseException;

/**
 * Unit test for CronScheduleBuilder.
 *
 * @author jhouse
 */
public class CronScheduleBuilderTest extends TestCase {

    public void testAtHourAndMinuteOnGivenDaysOfWeek() {

        CronTrigger trigger = newTrigger().withIdentity("test")
                .withSchedule(
                        atHourAndMinuteOnGivenDaysOfWeek(10, 0, DateBuilder.MONDAY, DateBuilder.THURSDAY, DateBuilder.FRIDAY))
                .build();
        Assert.assertEquals("0 0 10 ? * 2,5,6", trigger.getCronExpression());

        trigger = newTrigger().withIdentity("test")
                .withSchedule(
                        atHourAndMinuteOnGivenDaysOfWeek(10, 0, DateBuilder.WEDNESDAY))
                .build();
        Assert.assertEquals("0 0 10 ? * 4", trigger.getCronExpression());
    }

    @Test
    public void testBuild() throws ParseException {
        Trigger trigger = null;
        // 每周星期一、四、五10:00进行触发
        trigger = CronScheduleBuilder
                .atHourAndMinuteOnGivenDaysOfWeek(10, 0, DateBuilder.MONDAY, DateBuilder.THURSDAY, DateBuilder.FRIDAY).build();
        // 每天10:00进行触发
        trigger = CronScheduleBuilder.dailyAtHourAndMinute(10, 0).build();
        // 每月1日10:00进行触发
        trigger = CronScheduleBuilder.monthlyOnDayAndHourAndMinute(1, 10, 0).build();
        // 每周星期一10:00进行触发
        trigger = CronScheduleBuilder.weeklyOnDayAndHourAndMinute(DateBuilder.MONDAY, 10, 0).build();
        trigger = CronScheduleBuilder.cronSchedule("0 0 10 ? * 2,5,6").build();
        // 基于cron表达式对象,可能抛出ParseException异常
        trigger = CronScheduleBuilder.cronSchedule(new CronExpression("0 0 10 ? * 2,5,6")).build();
        // 基于cron表达式字符串,可能抛出ParseException异常
        CronScheduleBuilder.cronScheduleNonvalidatedExpression("0 0 10 ? * 2,5,6");

    }


}
