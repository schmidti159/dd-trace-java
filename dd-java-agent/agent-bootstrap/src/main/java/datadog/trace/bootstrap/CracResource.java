package datadog.trace.bootstrap;

import datadog.trace.api.Config;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.crac.CracHooks;
import datadog.trace.util.RandomUtils;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers one {@code jdk.crac.Resource} that runs the {@link CracHooks}.
 *
 * <p>The agent is compiled for Java 8, so the CRaC API is reached by reflection and the resource is
 * a {@link Proxy}. Without a CRaC-capable JDK nothing is registered. It is registered during
 * premain, so its {@code beforeCheckpoint} runs after the application's resources and its {@code
 * afterRestore} before them.
 */
final class CracResource implements InvocationHandler {
  private static final Logger log = LoggerFactory.getLogger(CracResource.class);

  /** The CRaC global context references resources weakly. */
  private static Object resource;

  private CracResource() {}

  static void register() {
    try {
      ClassLoader loader = ClassLoader.getSystemClassLoader();
      Class<?> resourceType = Class.forName("jdk.crac.Resource", false, loader);
      Class<?> coreType = Class.forName("jdk.crac.Core", false, loader);
      // the global context's class is not exported, so call through the public Context type
      Class<?> contextType = Class.forName("jdk.crac.Context", false, loader);
      Object context = coreType.getMethod("getGlobalContext").invoke(null);
      Object proxy =
          Proxy.newProxyInstance(loader, new Class<?>[] {resourceType}, new CracResource());
      contextType.getMethod("register", resourceType).invoke(context, proxy);
      resource = proxy;
      CracHooks.register(CracHooks.Phase.IDENTITY, new IdentityHook());
      log.info("Registered CRaC resource");
    } catch (ClassNotFoundException e) {
      log.debug("Not a CRaC JDK: {}", e.getMessage());
    } catch (Throwable t) {
      log.warn("Cannot register CRaC resource", t);
    }
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    switch (method.getName()) {
      case "beforeCheckpoint":
        CracHooks.beforeCheckpoint();
        return null;
      case "afterRestore":
        CracHooks.afterRestore();
        return null;
      case "hashCode":
        return System.identityHashCode(proxy);
      case "equals":
        return proxy == args[0];
      case "toString":
        return "datadog.trace.bootstrap.CracResource";
      default:
        return null;
    }
  }

  /** Configuration, runtime id, PID, host name and id generator seeds. */
  private static final class IdentityHook implements CracHooks.Hook {
    @Override
    public void beforeCheckpoint() {}

    @Override
    public void afterRestore() {
      // NativePRNG is re-seeded on restore; ThreadLocalRandom is not
      SecureRandom random = new SecureRandom();
      IdGenerationStrategy.reseedAfterRestore(random.nextLong());
      RandomUtils.reseedAfterRestore(random.nextLong(), random.nextLong());
      Config.reloadAfterRestore();
      Config config = Config.get();
      log.info(
          "Restored from CRaC checkpoint: env={}, version={}, runtime-id={}, pid={}",
          config.getEnv(),
          config.getVersion(),
          config.getRuntimeId(),
          config.getProcessId());
    }
  }
}
