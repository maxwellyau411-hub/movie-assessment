import { describe, expect, it } from 'vitest';
import { describeError, isStale } from './errors';
import { GraphQLRequestError } from './graphql';

describe('describeError', () => {
  it('tells the user to reload on a stale version, and shows other conflicts as the server wrote them', () => {
    const stale = new GraphQLRequestError('Movie 1 was changed', 'CONFLICT', 200, {
      reason: 'STALE_VERSION',
    });
    const inUse = new GraphQLRequestError(
      'Ilse is credited in 2 movies. Remove those credits before deleting.',
      'CONFLICT',
      200,
      {
        reason: 'PERSON_HAS_CREDITS',
      },
    );

    expect(isStale(stale)).toBe(true);
    expect(describeError(stale)).toMatch(/Reload/);
    expect(describeError(inUse)).toBe('Ilse is credited in 2 movies. Remove those credits before deleting.');
  });

  it('points at the fields when there are field errors, and hides server internals', () => {
    const fields = new GraphQLRequestError('Some fields are invalid', 'VALIDATION_ERROR', 200, {
      fieldErrors: [{ field: 'title', message: 'is required' }],
    });

    expect(describeError(fields)).toBe('Please correct the highlighted fields.');
    expect(
      describeError(new GraphQLRequestError('NullPointerException at …', 'INTERNAL_ERROR', 200)),
    ).not.toMatch(/Null/);
    expect(describeError(new GraphQLRequestError('x', 'NETWORK_ERROR', 0))).toMatch(/connection/);
    expect(describeError(new Error('boom'))).toBe('Something went wrong. Please try again.');
  });
});
