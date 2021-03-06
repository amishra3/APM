/*-
 * ========================LICENSE_START=================================
 * AEM Permission Management
 * %%
 * Copyright (C) 2013 Cognifide Limited
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package com.cognifide.cq.cqsm.core.actions;

import com.cognifide.cq.cqsm.api.actions.Action;
import com.cognifide.cq.cqsm.api.actions.ActionDescriptor;
import com.cognifide.cq.cqsm.api.actions.ActionFactory;
import com.cognifide.cq.cqsm.api.actions.ActionMapper;
import com.cognifide.cq.cqsm.api.actions.annotations.Mapper;
import com.cognifide.cq.cqsm.api.actions.annotations.Mapping;
import com.cognifide.cq.cqsm.api.exceptions.ActionCreationException;
import com.cognifide.cq.cqsm.core.Cqsm;
import com.cognifide.cq.cqsm.core.actions.scanner.AnnotatedClassRegistry;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component(immediate = true)
@Service
@Properties({@Property(name = Constants.SERVICE_DESCRIPTION, value = "Action factory service"),
		@Property(name = Constants.SERVICE_VENDOR, value = Cqsm.VENDOR_NAME)})
public class ActionFactoryImpl implements ActionFactory {

	private static final Logger LOG = LoggerFactory.getLogger(ActionFactoryImpl.class);

	private static final String BUNDLE_HEADER = "CQ-Security-Management-Actions";

	private AnnotatedClassRegistry registry;

	private List<Object> mappers = Collections.synchronizedList(new ArrayList<>());

	private boolean updated = false;

	@Activate
	public void activate(ComponentContext componentContext) {
		registry = new AnnotatedClassRegistry(componentContext.getBundleContext(), BUNDLE_HEADER,
				Mapper.class);
		registry.open();
	}

	@Deactivate
	public void deactivate() {
		if (registry != null) {
			registry.close();
		}
	}

	@Override
	public ActionDescriptor evaluate(String command) throws ActionCreationException {
		for (Object mapper : getMappers()) {
			for (Method method : mapper.getClass().getDeclaredMethods()) {
				if (!method.isAnnotationPresent(Mapping.class)) {
					continue;
				}

				final Mapping annotation = method.getAnnotation(Mapping.class);

				for (final String regex : annotation.value()) {
					final Pattern pattern = Pattern.compile("^" + regex + "$");
					final Matcher matcher = pattern.matcher(command);

					if (matcher.matches()) {
						final List<Object> args = new ArrayList<>();
						final List<String> rawArgs = new ArrayList<>();
						final Type[] parameterTypes = method.getGenericParameterTypes();

						for (int i = 1; i <= matcher.groupCount(); i++) {
							rawArgs.add(matcher.group(i));

							if (mapper instanceof ActionMapper) {
								args.add(((ActionMapper) mapper)
										.mapParameter(matcher.group(i), parameterTypes[i - 1]));
							} else {
								args.add(matcher.group(i));
							}
						}

						try {
							return new ActionDescriptor(command,
									(Action) method.invoke(mapper, args.toArray()), rawArgs);
						} catch (IllegalAccessException e) {
							LOG.error("Cannot access action mapper method: {} while processing command: {}",
									e.getMessage(), command);
						} catch (InvocationTargetException e) {
							LOG.error("Cannot invoke action mapper method: {} while processing command: {}",
									e.getMessage(), command);
						}
					}
				}
			}
		}

		throw new ActionCreationException(String.format("Cannot find action for command: %s", command));
	}

	@Override
	public synchronized boolean update() {
		if (registry == null) {
			LOG.error("Cannot update action factory (bundle not activated)");

			return false;
		}

		final List<Class<?>> classes = registry.getClasses();

		mappers.clear();
		for (Class clazz : classes) {
			try {
				mappers.add(clazz.newInstance());
			} catch (InstantiationException e) {
				LOG.error("Cannot instantiate action mapper: {}", e.getMessage());
			} catch (IllegalAccessException e) {
				LOG.error("Cannot construct action mapper, is constructor non public? {}", e.getMessage());
			}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("Created {} action mappers from {} classes", mappers.size(), classes.size());
		}

		return true;
	}

	@Override
	public List<Map<String, Object>> refer() {
		final List<Map<String, Object>> references = new ArrayList<>();

		for (Object mapper : getMappers()) {
			for (Method method : mapper.getClass().getDeclaredMethods()) {
				if (!method.isAnnotationPresent(Mapping.class) || !(mapper instanceof ActionMapper)) {
					continue;
				}

				final Mapping mapping = method.getAnnotation(Mapping.class);
				final List<String> commands = ((ActionMapper) mapper).referMapping(mapping);

				HashMap<String, Object> reference = new HashMap<>();
				reference.put("commands", commands);
				reference.put("pattern", mapping.value());
				reference.put("args", mapping.args());
				reference.put("reference", mapping.reference());

				references.add(reference);
			}
		}

		sortReferences(references);

		return references;
	}

	private void sortReferences(List<Map<String, Object>> references) {
		Collections.sort(references, new Comparator<Map<String, Object>>() {
			@Override
			public int compare(Map<String, Object> object1, Map<String, Object> object2) {
				List<String> commands1 = (List<String>) object1.get("commands");
				List<String> commands2 = (List<String>) object2.get("commands");
				String command1 = commands1.get(0);
				String command2 = commands2.get(0);
				return command1.compareToIgnoreCase(command2);
			}
		});
	}

	protected synchronized List<Object> getMappers() {
		if (!updated) {
			update();
			updated = true;
		}

		return mappers;
	}

}
