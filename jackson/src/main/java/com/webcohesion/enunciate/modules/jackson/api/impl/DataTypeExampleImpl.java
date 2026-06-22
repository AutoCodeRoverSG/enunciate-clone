/**
 * Copyright © 2006-2016 Web Cohesion (info@webcohesion.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webcohesion.enunciate.modules.jackson.api.impl;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webcohesion.enunciate.EnunciateException;
import com.webcohesion.enunciate.EnunciateLogger;
import com.webcohesion.enunciate.api.ApiRegistrationContext;
import com.webcohesion.enunciate.api.datatype.DataTypeReference;
import com.webcohesion.enunciate.facets.FacetFilter;
import com.webcohesion.enunciate.javac.decorations.Annotations;
import com.webcohesion.enunciate.javac.decorations.element.DecoratedElement;
import com.webcohesion.enunciate.javac.decorations.element.ElementUtils;
import com.webcohesion.enunciate.javac.decorations.type.DecoratedDeclaredType;
import com.webcohesion.enunciate.javac.decorations.type.DecoratedTypeMirror;
import com.webcohesion.enunciate.javac.javadoc.JavaDoc;
import com.webcohesion.enunciate.metadata.DocumentationExample;
import com.webcohesion.enunciate.modules.jackson.model.*;
import com.webcohesion.enunciate.modules.jackson.model.types.*;
import com.webcohesion.enunciate.util.ExampleUtils;
import com.webcohesion.enunciate.util.TypeHintUtils;

import javax.annotation.Nonnull;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * @author Ryan Heaton
 */
public class DataTypeExampleImpl extends ExampleImpl {

  private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final ObjectTypeDefinition type;
  private final List<DataTypeReference.ContainerType> containers;
  private final ApiRegistrationContext registrationContext;

  public DataTypeExampleImpl(ObjectTypeDefinition type, ApiRegistrationContext registrationContext) {
    this(type, null, registrationContext);
  }

  public DataTypeExampleImpl(ObjectTypeDefinition typeDefinition, List<DataTypeReference.ContainerType> containers, ApiRegistrationContext registrationContext) {
    this.type = typeDefinition;
    this.containers = containers == null ? Collections.<DataTypeReference.ContainerType>emptyList() : containers;
    this.registrationContext = registrationContext;
  }

  @Override
  public String getBody() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();

    Context context = new Context();
    context.stack = new LinkedList<String>();
    build(node, this.type, this.type, context);

    if (this.type.getContext().isWrapRootValue()) {
      ObjectNode wrappedNode = JsonNodeFactory.instance.objectNode();
      wrappedNode.set(this.type.getJsonRootName(), node);
      node = wrappedNode;
    }

    if (isWrappedSubclass(this.type)) {
      ObjectNode wrappedNode = JsonNodeFactory.instance.objectNode();
      wrappedNode.set(this.type.getTypeIdValue(), node);
      node = wrappedNode;
    }

    JsonNode outer = node;
    for (DataTypeReference.ContainerType container : this.containers) {
      switch (container) {
        case array:
        case collection:
        case list:
          ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
          arrayNode.add(outer);
          outer = arrayNode;
          break;
        case map:
          ObjectNode mapNode = JsonNodeFactory.instance.objectNode();
          mapNode.set("...", outer);
          outer = mapNode;
          break;
      }
    }

    try {
      return MAPPER.writeValueAsString(outer);
    }
    catch (JsonProcessingException e) {
      throw new EnunciateException(e);
    }
  }

  private boolean isWrappedSubclass(ObjectTypeDefinition type) {
    if (type.isAbstract() || type.isInterface()) {
      return false;
    }

    JsonType supertype = type.getSupertype();
    if (supertype instanceof JsonClassType) {
      TypeDefinition typeDefinition = ((JsonClassType) supertype).getTypeDefinition();
      if (typeDefinition.getTypeIdInclusion() == JsonTypeInfo.As.WRAPPER_OBJECT) {
        return true;
      }
      else if (typeDefinition instanceof ObjectTypeDefinition) {
        return isWrappedSubclass((ObjectTypeDefinition) typeDefinition);
      }
    }
    return false;
  }

  private void build(ObjectNode node, ObjectTypeDefinition type, @Nonnull ObjectTypeDefinition sourceType, Context context) {
    if (context.stack.size() > 2) {
      //don't go deeper than 2 for fear of the OOM (see https://github.com/stoicflame/enunciate/issues/139).
      return;
    }

    addTypeIdProperty(node, type, sourceType);
    if (applyTypeOverride(node, type)) {
      return;
    }

    for (Member member : type.getMembers()) {
      processMember(node, type, member, context);
    }

    buildSupertype(node, sourceType, type, context);
    addWildcardExtensions(node, type);
  }

  private void addTypeIdProperty(ObjectNode node, ObjectTypeDefinition type, ObjectTypeDefinition sourceType) {
    if (type.getTypeIdInclusion() == JsonTypeInfo.As.PROPERTY && type.getTypeIdProperty() != null) {
      node.put(type.getTypeIdProperty(), sourceType.getTypeIdValue());
    }
  }

  private boolean applyTypeOverride(ObjectNode node, ObjectTypeDefinition type) {
    JsonNode override = findExampleOverride(type, type.getContext().getContext().getLogger());
    if (override == null) {
      return false;
    }
    if (override instanceof ObjectNode objectNode) {
      node.setAll(objectNode);
      return true;
    }

    type.getContext().getContext().getLogger().warn("JSON example override of %s can't be used because it's not a JSON object.", type.getQualifiedName());
    return false;
  }

  private void processMember(ObjectNode node, ObjectTypeDefinition type, Member member, Context context) {
    if (shouldSkipMember(node, member)) {
      return;
    }

    JsonNode memberOverride = findExampleOverride(member, type.getContext().getContext().getLogger());
    if (memberOverride != null) {
      node.set(member.getName(), memberOverride);
      return;
    }

    MemberExample memberExample = resolveMemberExample(type, member);
    if (memberExample.exclude) {
      return;
    }

    swapExamplesIfNeeded(memberExample, context);
    setMemberExample(node, member, memberExample, context);
  }

  private boolean shouldSkipMember(ObjectNode node, Member member) {
    if (node.has(member.getName())) {
      return true;
    }
    if (!this.registrationContext.getFacetFilter().accept(member)) {
      return true;
    }
    return ElementUtils.findDeprecationMessage(member, null) != null;
  }

  private MemberExample resolveMemberExample(ObjectTypeDefinition type, Member member) {
    MemberExample memberExample = new MemberExample();
    applyDocumentationExampleTags(member, memberExample);
    applyDocumentationType(type, member, memberExample);
    if (applyDocumentationExample(type, member, memberExample)) {
      return memberExample;
    }
    applySpecifiedTypeInfoValue(type, member, memberExample);
    applyConfiguredExample(member, memberExample);
    return memberExample;
  }

  private void applyDocumentationExampleTags(Member member, MemberExample memberExample) {
    JavaDoc.JavaDocTagList tags = getDocumentationExampleTags(member);
    if (tags == null || tags.isEmpty()) {
      return;
    }

    memberExample.example = normalizeExample(tags.get(0));
    memberExample.example2 = memberExample.example;
    if (tags.size() > 1) {
      memberExample.example2 = normalizeExample(tags.get(1));
    }
  }

  private void applyDocumentationType(ObjectTypeDefinition type, Member member, MemberExample memberExample) {
    JavaDoc.JavaDocTagList tags = member.getJavaDoc().get("documentationType");
    if (tags == null || tags.isEmpty()) {
      return;
    }

    String tag = tags.get(0).trim();
    if (tag.isEmpty()) {
      return;
    }

    TypeElement typeElement = type.getContext().getContext().getProcessingEnvironment().getElementUtils().getTypeElement(tag);
    if (typeElement != null) {
      memberExample.exampleType = JsonTypeFactory.getJsonType(typeElement.asType(), type.getContext());
      return;
    }

    type.getContext().getContext().getLogger().warn("Invalid documentation type %s.", tag);
  }

  private boolean applyDocumentationExample(ObjectTypeDefinition type, Member member, MemberExample memberExample) {
    DocumentationExample documentationExample = getDocumentationExample(member);
    if (documentationExample == null) {
      return false;
    }
    if (documentationExample.exclude()) {
      memberExample.exclude = true;
      return true;
    }

    memberExample.example = defaultToNull(documentationExample.value());
    memberExample.example2 = defaultToNull(documentationExample.value2());
    TypeMirror typeHint = TypeHintUtils.getTypeHint(documentationExample.type(), type.getContext().getContext().getProcessingEnvironment(), null);
    if (typeHint != null) {
      memberExample.exampleType = JsonTypeFactory.getJsonType(typeHint, type.getContext());
    }
    return false;
  }

  private void applySpecifiedTypeInfoValue(ObjectTypeDefinition type, Member member, MemberExample memberExample) {
    String specifiedTypeInfoValue = findSpecifiedTypeInfoValue(member, type.getQualifiedName().toString(), type);
    if (specifiedTypeInfoValue != null) {
      memberExample.example = specifiedTypeInfoValue;
      memberExample.example2 = specifiedTypeInfoValue;
    }
  }

  private void applyConfiguredExample(Member member, MemberExample memberExample) {
    String configuredExample = getConfiguredExample(member);
    if (configuredExample != null) {
      memberExample.example = configuredExample;
      memberExample.example2 = configuredExample;
    }
  }

  private void swapExamplesIfNeeded(MemberExample memberExample, Context context) {
    if (context.currentIndex % 2 > 0) {
      //if our index is odd, switch example 1 and example 2.
      String placeholder = memberExample.example2;
      memberExample.example2 = memberExample.example;
      memberExample.example = placeholder;
    }
  }

  private void setMemberExample(ObjectNode node, Member member, MemberExample memberExample, Context context) {
    if (member.getChoices().size() <= 1) {
      JsonType jsonType = resolveJsonType(member, memberExample.exampleType);
      node.set(member.getName(), exampleNode(jsonType, memberExample.example, memberExample.example2, context));
      return;
    }

    if (member.isCollectionType()) {
      node.set(member.getName(), buildChoiceCollectionExample(node, member, memberExample, context));
      return;
    }

    for (Member choice : member.getChoices()) {
      JsonNode exampleNode = buildChoiceNode(node, member, choice, memberExample, context);
      node.set(member.getName(), exampleNode);
    }
  }

  private ArrayNode buildChoiceCollectionExample(ObjectNode node, Member member, MemberExample memberExample, Context context) {
    ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
    for (Member choice : member.getChoices()) {
      arrayNode.add(buildChoiceNode(node, member, choice, memberExample, context));
    }
    return arrayNode;
  }

  private JsonNode buildChoiceNode(ObjectNode node, Member member, Member choice, MemberExample memberExample, Context context) {
    JsonType jsonType = resolveJsonType(choice, memberExample.exampleType);
    String choiceName = normalizeChoiceName(choice.getName());
    if (member.getSubtypeIdInclusion() == JsonTypeInfo.As.WRAPPER_ARRAY) {
      ArrayNode wrapperNode = JsonNodeFactory.instance.arrayNode();
      wrapperNode.add(choiceName);
      wrapperNode.add(exampleNode(jsonType, memberExample.example, memberExample.example2, context));
      return wrapperNode;
    }
    if (member.getSubtypeIdInclusion() == JsonTypeInfo.As.WRAPPER_OBJECT) {
      ObjectNode wrapperNode = JsonNodeFactory.instance.objectNode();
      wrapperNode.set(choiceName, exampleNode(jsonType, memberExample.example, memberExample.example2, context));
      return wrapperNode;
    }

    JsonNode exampleNode = exampleNode(jsonType, memberExample.example, memberExample.example2, context);
    applySubtypeProperties(node, member, exampleNode);
    return exampleNode;
  }

  private JsonType resolveJsonType(Member member, JsonType exampleType) {
    return exampleType == null ? member.getJsonType() : exampleType;
  }

  private String normalizeChoiceName(String choiceName) {
    return "".equals(choiceName) ? "..." : choiceName;
  }

  private void applySubtypeProperties(ObjectNode node, Member member, JsonNode exampleNode) {
    if (member.getSubtypeIdInclusion() == JsonTypeInfo.As.PROPERTY) {
      if (member.getSubtypeIdProperty() != null && exampleNode instanceof ObjectNode objectNode) {
        objectNode.put(member.getSubtypeIdProperty(), "...");
      }
    }
    else if (member.getSubtypeIdInclusion() == JsonTypeInfo.As.EXTERNAL_PROPERTY && member.getSubtypeIdProperty() != null) {
      node.put(member.getSubtypeIdProperty(), "...");
    }
  }

  private void buildSupertype(ObjectNode node, ObjectTypeDefinition sourceType, ObjectTypeDefinition type, Context context) {
    JsonType supertype = type.getSupertype();
    if (supertype instanceof JsonClassType jsonClassType && jsonClassType.getTypeDefinition() instanceof ObjectTypeDefinition objectTypeDefinition) {
      build(node, objectTypeDefinition, sourceType, context);
    }
  }

  private void addWildcardExtensions(ObjectNode node, ObjectTypeDefinition type) {
    if (type.getWildcardMember() != null && ElementUtils.findDeprecationMessage(type.getWildcardMember(), null) == null
       && !ExampleUtils.isExcluded(type.getWildcardMember())) {
      node.put("extension1", "...");
      node.put("extension2", "...");
    }
  }

  private String normalizeExample(String example) {
    return defaultToNull(example == null ? null : example.trim());
  }

  private String defaultToNull(String example) {
    if (example == null || example.isEmpty() || "##default".equals(example)) {
      return null;
    }
    return example;
  }

  private static class MemberExample {
    private String example;
    private String example2;
    private JsonType exampleType;
    private boolean exclude;
  }

  private JsonNode findExampleOverride(DecoratedElement el, EnunciateLogger logger) {
    String overrideValue = null;

    JavaDoc.JavaDocTagList overrideTags = el.getJavaDoc().get("jsonExampleOverride");
    if (overrideTags != null && !overrideTags.isEmpty()) {
      overrideValue = overrideTags.get(0);
    }

    DocumentationExample annotation = (DocumentationExample) el.getAnnotation(DocumentationExample.class);
    if (annotation != null && !"##default".equals(annotation.jsonOverride())) {
      overrideValue = annotation.jsonOverride();
    }

    if (overrideValue != null) {
      try {
        return MAPPER.readTree(overrideValue);
      }
      catch (Exception e) {
        logger.error("Unable to parse example override of element %s: %s", el.toString(), e.getMessage());
      }
    }

    return null;
  }

  private DocumentationExample getDocumentationExample(Member member) {
    DocumentationExample annotation = member.getAnnotation(DocumentationExample.class);
    if (annotation == null) {
      DecoratedTypeMirror accessorType = member.getBareAccessorType();
      if (accessorType instanceof DecoratedDeclaredType) {
        annotation = ((DecoratedDeclaredType) accessorType).asElement().getAnnotation(DocumentationExample.class);
      }
    }
    return annotation;
  }

  private JavaDoc.JavaDocTagList getDocumentationExampleTags(Member member) {
    JavaDoc.JavaDocTagList tags = member.getJavaDoc().get("documentationExample");
    if (tags == null || tags.isEmpty()) {
      DecoratedTypeMirror accessorType = member.getBareAccessorType();
      if (accessorType instanceof DecoratedDeclaredType) {
        Element element = ((DecoratedDeclaredType) accessorType).asElement();
        tags = element instanceof DecoratedElement ? ((DecoratedElement) element).getJavaDoc().get("documentationExample") : null;
      }
    }
    return tags;
  }

  private String getConfiguredExample(Member member) {
    String configuredExample = null;
    DecoratedTypeMirror accessorType = member.getBareAccessorType();
    if (accessorType instanceof DecoratedDeclaredType) {
      Element element = ((DecoratedDeclaredType) accessorType).asElement();
      if (element instanceof TypeElement) {
        configuredExample = member.getContext().lookupExternalExample((TypeElement) element);
      }
    }
    return configuredExample;
  }

  private String findSpecifiedTypeInfoValue(Member member, String specifiedType, TypeDefinition type) {
    if (type == null) {
      return null;
    }
    else if (type.getTypeIdType() == JsonTypeInfo.Id.NAME && member.getSimpleName().toString().equals(type.getTypeIdProperty())) {
      JsonSubTypes subTypes = type.getAnnotation(JsonSubTypes.class);
      if (subTypes != null) {
        for (final JsonSubTypes.Type element : subTypes.value()) {
          DecoratedTypeMirror choiceType = Annotations.mirrorOf(element::value, type.getContext().getContext().getProcessingEnvironment());

          if (choiceType.isInstanceOf(specifiedType)) {
            return element.name();
          }
        }

        return null;
      }
    }

    JsonType supertype = type instanceof ObjectTypeDefinition ? ((ObjectTypeDefinition) type).getSupertype() : null;
    if (supertype instanceof JsonClassType) {
      return findSpecifiedTypeInfoValue(member, specifiedType, ((JsonClassType) supertype).getTypeDefinition());
    }

    return null;
  }

  private JsonNode exampleNode(JsonType jsonType, String specifiedExample, String specifiedExample2, Context context) {
    if (jsonType instanceof JsonClassType) {
      TypeDefinition typeDefinition = ((JsonClassType) jsonType).getTypeDefinition();
      if (typeDefinition instanceof ObjectTypeDefinition) {
        ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
        if (!context.stack.contains(typeDefinition.getQualifiedName().toString())) {
          context.stack.push(typeDefinition.getQualifiedName().toString());
          try {
            final ObjectTypeDefinition objTypeDef = (ObjectTypeDefinition) typeDefinition;
            build(objectNode, objTypeDef, objTypeDef, context);
          }
          finally {
            context.stack.pop();
          }
        }
        return objectNode;
      }
      else if (typeDefinition instanceof EnumTypeDefinition) {
        String example = "???";

        if (specifiedExample != null) {
          example = specifiedExample;
        }
        else {
          List<EnumValue> enumValues = ((EnumTypeDefinition) typeDefinition).getEnumValues();
          if (enumValues.size() > 0) {
            int index = new Random().nextInt(enumValues.size());
            example = enumValues.get(index).getValue();
          }
        }

        JsonType baseType = ((EnumTypeDefinition) typeDefinition).getBaseType();
        if (baseType.isBoolean()) {
          return JsonNodeFactory.instance.booleanNode(Boolean.valueOf(example));
        }
        else if (baseType.isWholeNumber()) {
          Long value;
          try {
            value = Long.valueOf(example);
          }
          catch (NumberFormatException e) {
            value = 123456L;
          }
          return JsonNodeFactory.instance.numberNode(value);
        }
        else if (baseType.isNumber()) {
          Double value;
          try {
            value = Double.valueOf(example);
          }
          catch (NumberFormatException e) {
            value = 12345.67890D;
          }
          return JsonNodeFactory.instance.numberNode(value);
        }
        else {
          return JsonNodeFactory.instance.textNode(example);
        }
      }
      else {
        return exampleNode(((SimpleTypeDefinition) typeDefinition).getBaseType(), specifiedExample, specifiedExample2, context);
      }
    }
    else if (jsonType instanceof JsonMapType) {
      ObjectNode mapNode = JsonNodeFactory.instance.objectNode();
      JsonType valueType = ((JsonMapType) jsonType).getValueType();
      String key1Example = "property1";
      if (specifiedExample != null) {
        int firstSpace = JavaDoc.indexOfFirstWhitespace(specifiedExample);
        if (firstSpace >= 0) {
          key1Example = specifiedExample.substring(0, firstSpace);
          specifiedExample = specifiedExample.substring(firstSpace + 1).trim();
          if (specifiedExample.isEmpty()) {
            specifiedExample = null;
          }
        }
      }

      String key2Example = "property2";
      if (specifiedExample2 != null) {
        int firstSpace = JavaDoc.indexOfFirstWhitespace(specifiedExample2);
        if (firstSpace >= 0) {
          key2Example = specifiedExample2.substring(0, firstSpace);
          specifiedExample2 = specifiedExample2.substring(firstSpace + 1).trim();
          if (specifiedExample2.isEmpty()) {
            specifiedExample2 = null;
          }
        }
      }

      mapNode.set(key1Example, exampleNode(valueType, specifiedExample, specifiedExample2, context));
      Context context2 = new Context();
      context2.stack = context.stack;
      context2.currentIndex = 1;
      mapNode.set(key2Example, exampleNode(valueType, specifiedExample2, specifiedExample, context2));
      return mapNode;
    }
    else if (jsonType.isArray()) {
      ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
      if (jsonType instanceof JsonArrayType) {
        JsonNode componentNode = exampleNode(((JsonArrayType) jsonType).getComponentType(), specifiedExample, specifiedExample2, context);
        arrayNode.add(componentNode);
        Context context2 = new Context();
        context2.stack = context.stack;
        context2.currentIndex = 1;
        JsonNode componentNode2 = exampleNode(((JsonArrayType) jsonType).getComponentType(), specifiedExample2, specifiedExample, context2);
        arrayNode.add(componentNode2);
      }
      return arrayNode;
    }
    else if (jsonType.isWholeNumber()) {
      Long example = 12345L;
      if (specifiedExample != null) {
        try {
          example = Long.parseLong(specifiedExample);
        }
        catch (NumberFormatException e) {
          this.type.getContext().getContext().getLogger().warn("\"%s\" was provided as a documentation example, but it is not a valid JSON whole number, so it will be ignored.", specifiedExample);
        }
      }
      return JsonNodeFactory.instance.numberNode(example);
    }
    else if (jsonType.isNumber()) {
      Double example = 12345D;
      if (specifiedExample != null) {
        try {
          example = Double.parseDouble(specifiedExample);
        }
        catch (NumberFormatException e) {
          this.type.getContext().getContext().getLogger().warn("\"%s\" was provided as a documentation example, but it is not a valid JSON number, so it will be ignored.", specifiedExample);
        }
      }
      return JsonNodeFactory.instance.numberNode(example);
    }
    else if (jsonType.isBoolean()) {
      boolean example = !"false".equals(specifiedExample);
      return JsonNodeFactory.instance.booleanNode(example);
    }
    else if (jsonType.isString()) {
      String example = specifiedExample;
      if (example == null) {
        example = "...";
      }
      return JsonNodeFactory.instance.textNode(example);
    }
    else {
      return JsonNodeFactory.instance.objectNode();
    }
  }

  private static class Context {
    LinkedList<String> stack;
    int currentIndex = 0;
  }
}
