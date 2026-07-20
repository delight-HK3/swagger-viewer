# Swagger Viewer

**Preview your Swagger documentation instantly inside JetBrains IDEA — no build required**

## Why I Built This

- To share API documentation written with Swagger, you typically need to build and deploy the application.
If the documentation contains a typo or incorrect content, you have to fix it and rebuild — which is time-consuming.
This plugin was developed to let you see how the documentation looks in advance, helping you catch mistakes before they happen.

## Key Features

### Real-Time Annotation Preview
- **Spring projects only** — targets Java/Kotlin projects based on Spring Boot or Spring MVC
- Scans Swagger annotations in `@RestController` / `@Controller` classes via PSI static analysis
- Supports `@Operation`, `@ApiResponse`, `@Parameter`, `@OpenAPIDefinition`, and more
- Supports OAS request body via `@Operation(requestBody = @RequestBody(...))` (Spring `@RequestBody` is not recognized)
- The Swagger UI preview in the Tool Window updates instantly as you type — no build, save, or app launch needed

### YAML / JSON Spec File Preview
- Automatically detects OpenAPI spec files (`.yaml`, `.yml`, `.json`) in your project
- Renders them in the Tool Window as soon as you open the file in the editor
- Preview updates in real time while editing the file

### IDE Integration
- Available as a Tool Window on the right side of the IDE — no separate browser needed
- Annotation tab and YAML tab are configured automatically (tabs with no relevant files are hidden)
- Works identically on both IntelliJ IDEA Community and Ultimate

## Requirements

- JetBrains IDE 2024.2 or later
- Spring Boot / Spring MVC based project (required for annotation preview)

## Getting Started

1. Install the plugin from the Marketplace
2. The **Swagger Viewer** Tool Window will appear automatically on the right side of the IDE

- **Annotation preview**: Open and edit a file containing `@RestController` or `@Operation` to see it reflected immediately in the Annotation tab
- **YAML/JSON preview**: Open an OpenAPI spec file (`.yaml`, `.yml`, `.json`) in the editor to view it in the YAML tab
