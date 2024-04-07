package org.quartz.impl.matchers;

import junit.framework.TestCase;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.quartz.JobKey;

@Slf4j
public class NameMatcherTest  {

    @Test
    public void testNameMatcher() {
        boolean rs = NameMatcher.jobNameContains("a")
                .isMatch(JobKey.jobKey("b"));
        System.out.println(rs);
    }

}