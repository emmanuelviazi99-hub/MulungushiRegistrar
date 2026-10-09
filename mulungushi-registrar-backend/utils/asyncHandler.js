// utils/asyncHandler.js
//
// Express does not automatically catch errors thrown inside an `async`
// route handler — without this wrapper, a rejected promise would hang the
// request instead of reaching our error handler. Wrapping every controller
// in asyncHandler forwards any thrown/rejected error to `next(err)`.
//
// Usage:
//   router.get('/students/:id', asyncHandler(studentController.getOne));

function asyncHandler(fn) {
  return function (req, res, next) {
    Promise.resolve(fn(req, res, next)).catch(next);
  };
}

module.exports = asyncHandler;
