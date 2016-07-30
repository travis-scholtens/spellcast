var cacheName = 'spelly-v0';
var urlsToCache = [
    ].map(function(url) { return new Request(url).url; });

self.addEventListener('install', function(event) {
  event.waitUntil(
      caches.open(cacheName).then(
          function(cache) {
            return cache.addAll(urlsToCache).then(cache.keys().then(
                function(requests) {
                  return Promise.all(
                      requests.filter(function(request) { return !request.url.endsWith('/'); }).map(
                          function(request) {
                            if (urlsToCache.indexOf(request.url) === -1)
                              return cache.delete(request);
                          }));
            }).then(
                function(deleted) {
                  if (deleted.some(function(result) { return result === true; }))
                    self.skipWaiting();
            }));
          }));
});

self.addEventListener('fetch', function(event) {
  event.respondWith(
    caches.match(event.request).then(
        function(response) {
          if (response)
            return response;

          return fetch(event.request.clone()).then(
              function(response) {
                if(!response || response.status !== 200 || response.type !== 'basic')
                  return response;

                if (urlsToCache.indexOf(event.request.url) !== -1) {
                  var responseToCache = response.clone();
                  caches.open(cacheName).then(
                      function(cache) {
                        cache.put(event.request, responseToCache);
                      });
                }
                return response;
              }).catch(function() { });
        }));
});
