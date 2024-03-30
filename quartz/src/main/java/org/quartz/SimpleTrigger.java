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

/**
 * A <code>{@link Trigger}</code> that is used to fire a <code>Job</code>
 * at a given moment in time, and optionally repeated at a specified interval.
 * 
 * @see TriggerBuilder
 * @see SimpleScheduleBuilder
 * 
 * @author James House
 * @author contributions by Lieven Govaerts of Ebitec Nv, Belgium.
 */
public interface SimpleTrigger extends Trigger {

    public static final long serialVersionUID = -3735980074222850397L;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be fired
     * now by <code>Scheduler</code>.
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> This instruction should typically only be used for
     * 'one-shot' (non-repeating) Triggers. If it is used on a trigger with a
     * repeat count &gt; 0 then it is equivalent to the instruction <code>{@link #MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT}
     * </code>.
     * </p>
     */
    /**
     * 如果在重复计数 > 0的触发器上使用它，
     * 那么它等效于指令 MISFIRE _ INstructions _ RESCHEDULE _ NOW _ WITHREMAINING _ REPEAT _ COUNT
     */
    // 具体来说，设置这个常量后，调度器会立即执行这个任务，
    // 而不是等待下一次的触发时间。这个常量在处理一些需要及时执行的任务时非常有用，例如发送延迟邮件、执行定时任务等。
    public static final int MISFIRE_INSTRUCTION_FIRE_NOW = 1;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to 'now' (even if the associated <code>{@link Calendar}</code>
     * excludes 'now') with the repeat count left as-is.  This does obey the
     * <code>Trigger</code> end-time however, so if 'now' is after the
     * end-time the <code>Trigger</code> will not fire again.
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> Use of this instruction causes the trigger to 'forget'
     * the start-time and repeat-count that it was originally setup with (this
     * is only an issue if you for some reason wanted to be able to tell what
     * the original values were at some later time).
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT = 2;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to 'now' (even if the associated <code>{@link Calendar}</code>
     * excludes 'now') with the repeat count set to what it would be, if it had
     * not missed any firings.  This does obey the <code>Trigger</code> end-time 
     * however, so if 'now' is after the end-time the <code>Trigger</code> will 
     * not fire again.
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> Use of this instruction causes the trigger to 'forget'
     * the start-time and repeat-count that it was originally setup with.
     * Instead, the repeat count on the trigger will be changed to whatever
     * the remaining repeat count is (this is only an issue if you for some
     * reason wanted to be able to tell what the original values were at some
     * later time).
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> This instruction could cause the <code>Trigger</code>
     * to go to the 'COMPLETE' state after firing 'now', if all the
     * repeat-fire-times where missed.
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT = 3;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to the next scheduled time after 'now' - taking into
     * account any associated <code>{@link Calendar}</code>, and with the
     * repeat count set to what it would be, if it had not missed any firings.
     * </p>
     * 
     * <p>
     * <i>NOTE/WARNING:</i> This instruction could cause the <code>Trigger</code>
     * to go directly to the 'COMPLETE' state if all fire-times where missed.
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT = 4;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to the next scheduled time after 'now' - taking into
     * account any associated <code>{@link Calendar}</code>, and with the
     * repeat count left unchanged.
     * </p>
     * 
     * <p>
     * <i>NOTE/WARNING:</i> This instruction could cause the <code>Trigger</code>
     * to go directly to the 'COMPLETE' state if the end-time of the trigger
     * has arrived.
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT = 5;
    
    /**
     * <p>
     * Used to indicate the 'repeat count' of the trigger is indefinite. Or in
     * other words, the trigger should repeat continually until the trigger's
     * ending timestamp.
     * </p>
     */
    // 不限次数执行
    public static final int REPEAT_INDEFINITELY = -1;

    /**
     *
     * <p>
     * Get the the number of times the <code>SimpleTrigger</code> should
     * repeat, after which it will be automatically deleted.
     * </p>
     * 
     * @see #REPEAT_INDEFINITELY
     */
    public int getRepeatCount();

    /**
     * <p>
     * Get the the time interval (in milliseconds) at which the <code>SimpleTrigger</code> should repeat.
     * </p>
     */
    /**
     * 获取 SimpleTrigger 应该重复的时间间隔(以毫秒为单位)。
     * @return
     */
    public long getRepeatInterval();
    
    /**
     * <p>
     * Get the number of times the <code>SimpleTrigger</code> has already fired.
     * </p>
     */
    /**
     * 获取 SimpleTrigger 已经触发的次数。
     * @return
     */
    public int getTimesTriggered();

    TriggerBuilder<SimpleTrigger> getTriggerBuilder();
}
