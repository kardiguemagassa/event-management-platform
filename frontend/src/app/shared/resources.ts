import { Resource } from '@angular/core';

/**
 * The value of a resource, or {@code undefined} while it has none (loading, idle, or failed). Reading
 * {@code value()} of a failed resource throws: computed signals read it through this instead.
 */
export function valueOf<T>(resource: Resource<T>): T | undefined {
  return resource.hasValue() ? resource.value() : undefined;
}
