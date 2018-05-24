## New and noteworthy

Here are the new features introduced in this Gradle release.

<!--
IMPORTANT: if this is a patch release, ensure that a prominent link is included in the foreword to all releases of the same minor stream.
Add-->

<!--
### Example new and noteworthy
-->

## Promoted features

Promoted features are features that were incubating in previous versions of Gradle but are now supported and subject to backwards compatibility.
See the User guide section on the “[Feature Lifecycle](userguide/feature_lifecycle.html)” for more information.

The following are the features that have been promoted in this Gradle release.

<!--
### Example promoted
-->

## Fixed issues

## Deprecations

Features that have become superseded or irrelevant due to the natural evolution of Gradle become *deprecated*, and scheduled to be removed
in the next major Gradle version (Gradle 5.0). See the User guide section on the “[Feature Lifecycle](userguide/feature_lifecycle.html)” for more information.

The following are the newly deprecated items in this Gradle release. If you have concerns about a deprecation, please raise it via the [Gradle Forums](https://discuss.gradle.org).

### Creating instances of JavaPluginConvention

Instances of this class are intended to be created only by the `java-base` plugin and should not be created directly. Creating instances using the constructor of `JavaPluginConvention` will become an error in Gradle 5.0. The class itself is not deprecated and it is still be possible to use the instances created by the `java-base` plugin.

## Potential breaking changes

<!--
### Example breaking change
-->

### Using Groovy GPath with `tasks.withType()`

In previous versions of Gradle, it was sometimes possible to use a [GPath](http://docs.groovy-lang.org/latest/html/documentation/#gpath_expressions) expression with a project's task collection to build a list of a single property for all tasks.

For instance, `tasks.withType(SomeTask).name` would create a list of `String`s containing all of the names of tasks of type `SomeTask`. This was only possible with the method [`TaskCollection.withType(Class)`](javadoc/org/gradle/api/tasks/TaskCollection.html#withType-java.lang.Class-).

Plugins or build scripts attempting to do this will now get a runtime exception.  The easiest fix is to explicitly use the [spread operator](http://docs.groovy-lang.org/latest/html/documentation/#_spread_operator).

## External contributions

We would like to thank the following community members for making contributions to this release of Gradle.

<!--
 - [Some person](https://github.com/some-person) - fixed some issue (gradle/gradle#1234)
-->

We love getting contributions from the Gradle community. For information on contributing, please see [gradle.org/contribute](https://gradle.org/contribute).

## Known issues

Known issues are problems that were discovered post release that are directly related to changes made in this release.
