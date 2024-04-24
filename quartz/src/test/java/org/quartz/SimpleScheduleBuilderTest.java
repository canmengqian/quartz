package org.quartz;

import junit.framework.TestCase;
import org.junit.Test;

public class SimpleScheduleBuilderTest extends TestCase {
    @Test
    public void testBuild() {
        // 每小时执行一次,不限执行次数
        SimpleScheduleBuilder.repeatHourlyForever();
        // 指定执行间隔为2小时一次,不限执行次数
        SimpleScheduleBuilder.repeatHourlyForever(2);
        // 每小时执行一次,限定执行10次
        SimpleScheduleBuilder.repeatHourlyForTotalCount(10);
        // 每小时执行一次,限定执行10次,间隔2小时
        SimpleScheduleBuilder.repeatHourlyForTotalCount(10, 2);
        // 每分钟执行一次,不限执行次数
        SimpleScheduleBuilder.repeatMinutelyForever();
        // 每10分钟执行一次,不限执行次数
        SimpleScheduleBuilder.repeatMinutelyForever(10);
        // 每分钟执行一次,限定执行10次
        SimpleScheduleBuilder.repeatMinutelyForTotalCount(10);
        // 每10秒执行一次,限定执行10次
        SimpleScheduleBuilder.repeatSecondlyForTotalCount(10, 10);
        //创建一个简单触发器，可以自定义指定执行间隔,执行次数和执行策略
        SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInHours(1).
                withRepeatCount(10)
                .withMisfireHandlingInstructionFireNow();
    }

}