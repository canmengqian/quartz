package org.quartz.spi;

/**
 * @author lorban
 * Trigger 执行的结果
 */
public class TriggerFiredResult {

  private TriggerFiredBundle triggerFiredBundle;

  /**
   * 触发器执行失败
   */
  private Exception exception;

  public TriggerFiredResult(TriggerFiredBundle triggerFiredBundle) {
    this.triggerFiredBundle = triggerFiredBundle;
  }

  public TriggerFiredResult(Exception exception) {
    this.exception = exception;
  }

  public TriggerFiredBundle getTriggerFiredBundle() {
    return triggerFiredBundle;
  }

  public Exception getException() {
    return exception;
  }
}
