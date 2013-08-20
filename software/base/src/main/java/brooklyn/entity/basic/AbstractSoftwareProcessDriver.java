package brooklyn.entity.basic;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.io.InputStream;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.location.Location;
import brooklyn.util.ResourceUtils;
import brooklyn.util.task.DynamicTasks;
import brooklyn.util.text.TemplateProcessor;

import com.google.common.collect.ImmutableMap;

/**
 * An abstract implementation of the {@link SoftwareProcessDriver}.
 */
public abstract class AbstractSoftwareProcessDriver implements SoftwareProcessDriver {

	private static final Logger log = LoggerFactory.getLogger(AbstractSoftwareProcessDriver.class);
	
    protected final EntityLocal entity;
    private final Location location;
    
    public AbstractSoftwareProcessDriver(EntityLocal entity, Location location) {
        this.entity = checkNotNull(entity, "entity");
        this.location = checkNotNull(location, "location");
    }
	
    /*
     * (non-Javadoc)
     * @see brooklyn.entity.basic.SoftwareProcessDriver#rebind()
     */
    @Override
    public void rebind() {
        // no-op
    }

    /**
     * Start the entity.
     *
     * this installs, configures and launches the application process. However,
     * users can also call the {@link #install()}, {@link #customize()} and
     * {@link #launch()} steps independently. The {@link #postLaunch()} will
     * be called after the {@link #launch()} metheod is executed, but the
     * process may not be completely initialised at this stage, so care is
     * required when implementing these stages.
     *
     * @see #stop()
     */
	@Override
	public void start() {
        new DynamicTasks.AutoQueueVoid("install") { protected void main() { 
            waitForConfigKey(ConfigKeys.INSTALL_LATCH);
            install();
        }};
        
        new DynamicTasks.AutoQueueVoid("customize") { protected void main() { 
            waitForConfigKey(ConfigKeys.CUSTOMIZE_LATCH);
            customize();
        }};
        
        new DynamicTasks.AutoQueueVoid("launch") { protected void main() { 
            waitForConfigKey(ConfigKeys.LAUNCH_LATCH);
            launch();
        }};
        
        new DynamicTasks.AutoQueueVoid("post-launch") { protected void main() { 
            postLaunch();
        }};
	}

	@Override
	public abstract void stop();
	
	public abstract void install();
	public abstract void customize();
	public abstract void launch();
    
    @Override
    public void kill() {
        stop();
    }
    
    /**
     * Implement this method in child classes to add some post-launch behavior
     */
	public void postLaunch() {}
    
	@Override
	public void restart() {
	    new DynamicTasks.AutoQueueVoid("stop (if running)") { protected void main() {
	        boolean previouslyRunning = isRunning();
	        try {
	            getEntity().setAttribute(Attributes.SERVICE_STATE, Lifecycle.STOPPING);
	            stop();
	        } catch (Exception e) {
	            if (previouslyRunning) {
	                log.debug(getEntity() + " restart: stop failed, when was previously running", e);
	            } else {
	                log.debug(getEntity() + " restart: stop failed (but was not previously running, so not a surprise)", e);
	            }
	        }
	    }};
	    new DynamicTasks.AutoQueueVoid("launch") { protected void main() {
	        getEntity().setAttribute(Attributes.SERVICE_STATE, Lifecycle.STARTING);
	        launch();
        }};
	}
	
	public EntityLocal getEntity() { return entity; } 

	public Location getLocation() { return location; } 
    
    public InputStream getResource(String url) {
        return new ResourceUtils(entity).getResourceFromUrl(url);
    }
    
    public String getResourceAsString(String url) {
        return new ResourceUtils(entity).getResourceAsString(url);
    }

    public String processTemplate(File templateConfigFile, Map<String,Object> extraSubstitutions) {
        return processTemplate(templateConfigFile.toURI().toASCIIString(),extraSubstitutions);
    }

    public String processTemplate(File templateConfigFile) {
        return processTemplate(templateConfigFile.toURI().toASCIIString());
    }

    /** Takes the contents of a template file from the given URL (often a classpath://com/myco/myprod/myfile.conf or .sh)
     * and replaces "${entity.xxx}" with the result of entity.getXxx() and similar for other driver, location;
     * as well as replacing config keys on the management context
     * <p>
     * uses Freemarker templates under the covers
     **/  
    public String processTemplate(String templateConfigUrl) {
        return processTemplate(templateConfigUrl, ImmutableMap.<String,String>of());
    }

    public String processTemplate(String templateConfigUrl, Map<String,? extends Object> extraSubstitutions) {
        return processTemplateContents(getResourceAsString(templateConfigUrl), extraSubstitutions);
    }

    public String processTemplateContents(String templateContents) {
        return processTemplateContents(templateContents, ImmutableMap.<String,String>of());
    }
    
    public String processTemplateContents(String templateContents, Map<String,? extends Object> extraSubstitutions) {
        Map<String, Object> config = getEntity().getApplication().getManagementContext().getConfig().asMapWithStringKeys();
        Map<String, Object> substitutions = ImmutableMap.<String, Object>builder()
                .putAll(config)
                .put("entity", entity)
                .put("driver", this)
                .put("location", getLocation())
                .putAll(extraSubstitutions)
                .build();

        return TemplateProcessor.processTemplateContents(templateContents, substitutions);
        /*
        try {
            Configuration cfg = new Configuration();
            StringTemplateLoader templateLoader = new StringTemplateLoader();
            templateLoader.putTemplate("config", templateContents);
            cfg.setTemplateLoader(templateLoader);
            Template template = cfg.getTemplate("config");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Writer out = new OutputStreamWriter(baos);
            template.process(substitutions, out);
            out.flush();

            return new String(baos.toByteArray());
        } catch (Exception e) {
            log.warn("Error creating configuration file for "+entity, e);
            throw Exceptions.propagate(e);
        }
        */
    }
    
    protected void waitForConfigKey(ConfigKey<?> configKey) {
        Object val = entity.getConfig(configKey);
        if (val != null) log.debug("{} finished waiting for {} (value {}); continuing...", new Object[] {this, configKey, val});
    }
}
