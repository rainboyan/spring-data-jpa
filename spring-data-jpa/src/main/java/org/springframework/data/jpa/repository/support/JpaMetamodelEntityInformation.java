/*
 * Copyright 2011-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.repository.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.IdClass;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import jakarta.persistence.metamodel.Type.PersistenceType;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.data.jpa.provider.PersistenceProvider;
import org.springframework.data.jpa.util.JpaMetamodel;
import org.springframework.data.util.DirectFieldAccessFallbackBeanWrapper;
import org.springframework.data.util.ProxyUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Implementation of {@link org.springframework.data.repository.core.EntityInformation} that uses JPA {@link Metamodel}
 * to find the domain class' id field.
 *
 * @author Oliver Gierke
 * @author Thomas Darimont
 * @author Christoph Strobl
 * @author Mark Paluch
 * @author Jens Schauder
 */
public class JpaMetamodelEntityInformation<T, ID> extends JpaEntityInformationSupport<T, ID> {

	private final IdMetadata<T> idMetadata;
	private final Optional<SingularAttribute<? super T, ?>> versionAttribute;
	private final Metamodel metamodel;
	private final @Nullable String entityName;

	/**
	 * Creates a new {@link JpaMetamodelEntityInformation} for the given domain class and {@link Metamodel}.
	 *
	 * @param domainClass must not be {@literal null}.
	 * @param metamodel must not be {@literal null}.
	 */
	public JpaMetamodelEntityInformation(Class<T> domainClass, Metamodel metamodel) {

		super(domainClass);

		Assert.notNull(metamodel, "Metamodel must not be null!");
		this.metamodel = metamodel;

		ManagedType<T> type = metamodel.managedType(domainClass);

		if (type == null) {
			throw new IllegalArgumentException("The given domain class can not be found in the given Metamodel!");
		}

		this.entityName = type instanceof EntityType ? ((EntityType<?>) type).getName() : null;

		if (!(type instanceof IdentifiableType)) {
			throw new IllegalArgumentException("The given domain class does not contain an id attribute!");
		}

		IdentifiableType<T> identifiableType = (IdentifiableType<T>) type;

		this.idMetadata = new IdMetadata<>(identifiableType, PersistenceProvider.fromMetamodel(metamodel));
		this.versionAttribute = findVersionAttribute(identifiableType, metamodel);
	}

	@Override
	public String getEntityName() {
		return entityName != null ? entityName : super.getEntityName();
	}

	/**
	 * Returns the version attribute of the given {@link ManagedType} or {@literal null} if none available.
	 *
	 * @param type must not be {@literal null}.
	 * @param metamodel must not be {@literal null}.
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private static <T> Optional<SingularAttribute<? super T, ?>> findVersionAttribute(IdentifiableType<T> type,
			Metamodel metamodel) {

		try {
			return Optional.ofNullable(type.getVersion(Object.class));
		} catch (IllegalArgumentException o_O) {
			// Needs workarounds as the method is implemented with a strict type check on e.g. Hibernate < 4.3
		}

		Set<SingularAttribute<? super T, ?>> attributes = type.getSingularAttributes();

		for (SingularAttribute<? super T, ?> attribute : attributes) {
			if (attribute.isVersion()) {
				return Optional.of(attribute);
			}
		}

		Class<?> superType = type.getJavaType().getSuperclass();

		if (!JpaMetamodel.of(metamodel).isJpaManaged(superType)) {
			return Optional.empty();
		}

		ManagedType<?> managedSuperType = metamodel.managedType(superType);

		if (!(managedSuperType instanceof IdentifiableType)) {
			return Optional.empty();
		}

		return findVersionAttribute((IdentifiableType<T>) managedSuperType, metamodel);
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public ID getId(T entity) {

		// check if this is a proxy. If so use Proxy mechanics to access the id.
		PersistenceProvider persistenceProvider = PersistenceProvider.fromMetamodel(metamodel);

		if (persistenceProvider.shouldUseAccessorFor(entity)) {
			return (ID) persistenceProvider.getIdentifierFrom(entity);
		}

		// if not a proxy use Spring mechanics to access the id.
		BeanWrapper entityWrapper = new DirectFieldAccessFallbackBeanWrapper(entity);

		if (idMetadata.hasSimpleId()) {
			return (ID) entityWrapper.getPropertyValue(idMetadata.getSimpleIdAttribute().getName());
		}

		BeanWrapper idWrapper = new IdentifierDerivingDirectFieldAccessFallbackBeanWrapper(idMetadata.getType(), metamodel);
		boolean partialIdValueFound = false;

		for (SingularAttribute<? super T, ?> attribute : idMetadata) {
			Object propertyValue = entityWrapper.getPropertyValue(attribute.getName());

			if (propertyValue != null) {
				partialIdValueFound = true;
			}

			idWrapper.setPropertyValue(attribute.getName(), propertyValue);
		}

		return partialIdValueFound ? (ID) idWrapper.getWrappedInstance() : null;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Class<ID> getIdType() {
		return (Class<ID>) idMetadata.getType();
	}

	@Override
	public SingularAttribute<? super T, ?> getIdAttribute() {
		return idMetadata.getSimpleIdAttribute();
	}

	@Override
	public boolean hasCompositeId() {
		return !idMetadata.hasSimpleId();
	}

	@Override
	public Iterable<String> getIdAttributeNames() {

		List<String> attributeNames = new ArrayList<>(idMetadata.attributes.size());

		for (SingularAttribute<? super T, ?> attribute : idMetadata.attributes) {
			attributeNames.add(attribute.getName());
		}

		return attributeNames;
	}

	@Override
	public Object getCompositeIdAttributeValue(Object id, String idAttribute) {

		Assert.isTrue(hasCompositeId(), "Model must have a composite Id!");

		return new DirectFieldAccessFallbackBeanWrapper(id).getPropertyValue(idAttribute);
	}

	@Override
	public boolean isNew(T entity) {

		if (!versionAttribute.isPresent()
				|| versionAttribute.map(Attribute::getJavaType).map(Class::isPrimitive).orElse(false)) {
			return super.isNew(entity);
		}

		BeanWrapper wrapper = new DirectFieldAccessFallbackBeanWrapper(entity);

		return versionAttribute.map(it -> wrapper.getPropertyValue(it.getName()) == null).orElse(true);
	}

	/**
	 * Simple value object to encapsulate id specific metadata.
	 *
	 * @author Oliver Gierke
	 * @author Thomas Darimont
	 */
	private static class IdMetadata<T> implements Iterable<SingularAttribute<? super T, ?>> {

		private final IdentifiableType<T> type;
		private final Set<SingularAttribute<? super T, ?>> idClassAttributes;
		private final Set<SingularAttribute<? super T, ?>> attributes;
		private @Nullable Class<?> idType;

		@SuppressWarnings("unchecked")
		IdMetadata(IdentifiableType<T> source, PersistenceProvider persistenceProvider) {

			this.type = source;
			this.idClassAttributes = persistenceProvider.getIdClassAttributes(source);
			this.attributes = (Set<SingularAttribute<? super T, ?>>) (source.hasSingleIdAttribute()
					? Collections.singleton(source.getId(source.getIdType().getJavaType()))
					: source.getIdClassAttributes());
		}

		boolean hasSimpleId() {
			return idClassAttributes.isEmpty() && attributes.size() == 1;
		}

		public Class<?> getType() {

			if (idType != null) {
				return idType;
			}

			// lazy initialization of idType field with tolerable benign data-race
			this.idType = tryExtractIdTypeWithFallbackToIdTypeLookup();

			if (this.idType == null) {
				throw new IllegalStateException("Cannot resolve Id type from " + type);
			}

			return this.idType;
		}

		@Nullable
		private Class<?> tryExtractIdTypeWithFallbackToIdTypeLookup() {

			try {

				Class<?> idClassType = lookupIdClass(type);
				if (idClassType != null) {
					return idClassType;
				}

				Type<?> idType = type.getIdType();
				return idType == null ? null : idType.getJavaType();
			} catch (IllegalStateException e) {
				// see https://hibernate.onjira.com/browse/HHH-6951
				return null;
			}
		}

		@Nullable
		private static Class<?> lookupIdClass(IdentifiableType<?> type) {

			IdClass annotation = type.getJavaType() != null
					? AnnotationUtils.findAnnotation(type.getJavaType(), IdClass.class)
					: null;
			return annotation == null ? null : annotation.value();
		}

		SingularAttribute<? super T, ?> getSimpleIdAttribute() {
			return attributes.iterator().next();
		}

		@Override
		public Iterator<SingularAttribute<? super T, ?>> iterator() {
			return attributes.iterator();
		}
	}

	/**
	 * Custom extension of {@link DirectFieldAccessFallbackBeanWrapper} that allows to derive the identifier if composite
	 * keys with complex key attribute types (e.g. types that are annotated with {@code @Entity} themselves) are used.
	 *
	 * @author Thomas Darimont
	 */
	private static class IdentifierDerivingDirectFieldAccessFallbackBeanWrapper
			extends DirectFieldAccessFallbackBeanWrapper {

		private final Metamodel metamodel;
		private final JpaMetamodel jpaMetamodel;

		IdentifierDerivingDirectFieldAccessFallbackBeanWrapper(Class<?> type, Metamodel metamodel) {
			super(type);
			this.metamodel = metamodel;
			this.jpaMetamodel = JpaMetamodel.of(metamodel);
		}

		/**
		 * In addition to the functionality described in {@link BeanWrapperImpl} it is checked whether we have a nested
		 * entity that is part of the id key. If this is the case, we need to derive the identifier of the nested entity.
		 */
		@Override
		@SuppressWarnings({ "unchecked", "rawtypes" })
		public void setPropertyValue(String propertyName, @Nullable Object value) {

			if (!isIdentifierDerivationNecessary(value)) {
				super.setPropertyValue(propertyName, value);
				return;
			}

			// Derive the identifier from the nested entity that is part of the composite key.
			JpaMetamodelEntityInformation nestedEntityInformation = new JpaMetamodelEntityInformation(
					ProxyUtils.getUserClass(value), this.metamodel);

			if (!nestedEntityInformation.getJavaType().isAnnotationPresent(IdClass.class)) {

				Object nestedIdPropertyValue = new DirectFieldAccessFallbackBeanWrapper(value)
						.getPropertyValue(nestedEntityInformation.getRequiredIdAttribute().getName());
				super.setPropertyValue(propertyName, nestedIdPropertyValue);
				return;
			}

			// We have an IdClass property, we need to inspect the current value in order to map potentially multiple id
			// properties correctly.

			BeanWrapper sourceIdValueWrapper = new DirectFieldAccessFallbackBeanWrapper(value);
			BeanWrapper targetIdClassTypeWrapper = new BeanWrapperImpl(nestedEntityInformation.getIdType());

			for (String idAttributeName : (Iterable<String>) nestedEntityInformation.getIdAttributeNames()) {
				targetIdClassTypeWrapper.setPropertyValue(idAttributeName,
						extractActualIdPropertyValue(sourceIdValueWrapper, idAttributeName));
			}

			super.setPropertyValue(propertyName, targetIdClassTypeWrapper.getWrappedInstance());
		}

		@Nullable
		private Object extractActualIdPropertyValue(BeanWrapper sourceIdValueWrapper, String idAttributeName) {

			Object idPropertyValue = sourceIdValueWrapper.getPropertyValue(idAttributeName);

			if (idPropertyValue != null) {

				Class<?> idPropertyValueType = idPropertyValue.getClass();

				if (!jpaMetamodel.isJpaManaged(idPropertyValueType)) {
					return idPropertyValue;
				}

				return new DirectFieldAccessFallbackBeanWrapper(idPropertyValue)
						.getPropertyValue(tryFindSingularIdAttributeNameOrUseFallback(idPropertyValueType, idAttributeName));
			}

			return null;
		}

		private String tryFindSingularIdAttributeNameOrUseFallback(Class<?> idPropertyValueType,
				String fallbackIdTypePropertyName) {

			ManagedType<?> idPropertyType = metamodel.managedType(idPropertyValueType);
			for (SingularAttribute<?, ?> sa : idPropertyType.getSingularAttributes()) {
				if (sa.isId()) {
					return sa.getName();
				}
			}

			return fallbackIdTypePropertyName;
		}

		/**
		 * @param value
		 * @return {@literal true} if the given value is not {@literal null} and a mapped persistable entity otherwise
		 *         {@literal false}
		 */
		private boolean isIdentifierDerivationNecessary(@Nullable Object value) {

			if (value == null) {
				return false;
			}

			Class<?> userClass = ProxyUtils.getUserClass(value);

			if (!this.jpaMetamodel.isJpaManaged(userClass)) {
				return false;
			}

			ManagedType<?> managedType = this.metamodel.managedType(userClass);

			if (managedType == null) {
				throw new IllegalStateException("ManagedType must not be null. We checked that it exists before.");
			}

			return managedType.getPersistenceType() == PersistenceType.ENTITY;
		}
	}
}
