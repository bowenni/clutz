# Hacking on Clutz and Gents

## Prerequisites

- Java JDK version 7 or 8
- gradle 3.0
- NodeJS 5 or later, including NPM

## NPM dependencies

Clutz and Gents use some tools that are installed using npm (`clang-format` and
`typescript`, specifically). Run `npm install` in your project folder to
install them.

## Building

Run `gradle assemble` to build, `gradle test` to, well, test.

## IDE setup

Run `gradle eclipse` to generate Eclipse project configuration.

## Tests

You can run the test suite with:

```shell
$ ./gradlew test
```

Pass the environment variable `UPDATE_GOLDENS=y` to update the golden files.

### Running tests from an IDE

The `gradle` configuration makes sure that tests use the locally installed
`clang-format` by setting the system property `gents.clangFormat`, see
`build.gradle`. Make sure to configure your IDE's test runner to pass the same
property for reproducible results.

## Java Formatting

The project verifies that all java source is formatted properly with
(google-java-format)[https://github.com/google/google-java-format]. The
verification happens through `gradle verifyGoogleJavaFormat`.

You can manually reformat the source with `gradle googleJavaFormat`.
