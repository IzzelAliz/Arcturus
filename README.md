# Arcturus

A Java compiler plugin that implements [JEP 303](https://openjdk.org/jeps/303), a.k.a. user defined intrinsics.

## Usage

Example usage:

```java
public class UltimateAnswer {

  public static void main(String[] args) {
    int i = (int) UltimateAnswer.getAnswer();
    System.out.println(i);
  }

  private static final Intrinsic ANSWER = new Intrinsic(
    "answer",
    (lookup, methodType, args) -> {
      MethodHandle handle = MethodHandles.dropArguments(
        MethodHandles.constant(int.class, 42),
        0,
        methodType.parameterList()
      );
      return new ConstantCallSite(handle);
    });

  @Intrinsic.Link("ANSWER")
  public static native Object getAnswer(Object... args);
}
```

Outputs:
```
42
```

The call to `getAnswer` is translated to a `invokedynamic` call using `ANSWER` as bootstrap method.

Please refer to test sources for more details.

### Gradle

![](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Fmaven.izzel.io%2Freleases%2Fio%2Fizzel%2Farcturus%2Fmaven-metadata.xml&style=flat-square)

```groovy
repositories {
  maven { url 'https://maven.izzel.io/releases' }
}
dependencies {
  implementation 'io.izzel:arcturus:VERSION'
  annotationProcessor 'io.izzel:arcturus:VERSION:processor'
}
```

## License

This project is licensed under the MIT License.
