/*
 * Datadog Telemetry API for Logs by Ethan Tan
 * 
 * Imitated OpenAPI Generator format to keep in line with other API files
 */
package datadog.telemetry.api;

public class Log {

    @com.squareup.moshi.Json(name = "message")
    private String message;

    @com.squareup.moshi.Json(name = "level")
    private String level;

    @com.squareup.moshi.Json(name = "tags")
    private String tags; 

    @com.squareup.moshi.Json(name = "stack_trace")
    private String stack_trace; 

    /**
     * Get message
     * 
     * @return message
     */
    public String getMessage() { 
        return message;
    } 

    /** Set message */
    public void setMessage(String message) { 
        this.message = message;
    }

    public Log message(String message) { 
        this.message = message;
        return this;
    }

    /**
     * Get level 
     * 
     * @return level 
     */
    public String getLevel() { 
        return level;
    } 

    /** Set level */
    public void setLevel(String level) { 
        this.level = level;
    }

    public Log level(String level) { 
        this.level = level;
        return this;
    }

    /**
     * Get tags 
     * 
     * @return tags 
     */
    public String getTags() { 
        return tags;
    } 

    /** Set tags */
    public void setTags(String tags) { 
        this.tags = tags;
    }

    public Log tags(String tags) { 
        this.tags = tags;
        return this;
    }
   
    /**
     * Get stack trace 
     * 
     * @return stack_trace
     */
    public String getStackTrace() { 
        return stack_trace;
    } 

    /** Set stack trace */
    public void setStackTrace(String stackTrace) { 
        this.stack_trace = stackTrace;
    }

    public Log stackTrace(String stackTrace) { 
        this.stack_trace = stackTrace;
        return this;
    }

    /** Create a string representation of this pojo.  */
    @Override
    public String toString() { 
        StringBuilder sb = new StringBuilder();
        sb.append("class Log {\n");

        sb.append("    message: ").append(message).append("\n");
        sb.append("    level: ").append(level).append("\n");
        sb.append("    tags: ").append(tags).append("\n");
        sb.append("    stack_trace: ").append(stack_trace).append("\n");
        sb.append("}");
        return sb.toString();
    }
}
