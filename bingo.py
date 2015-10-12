values = {
  'a': 1,
  'b': 3,
  'c': 3,
  'd': 2,
  'e': 1,
  'f': 4,
  'g': 2,
  'h': 4,
  'i': 1,
  'j': 8,
  'k': 5,
  'l': 1,
  'm': 3,
  'n': 1,
  'o': 1,
  'p': 3,
  'q': 10,
  'r': 1,
  's': 1,
  't': 1,
  'u': 1,
  'v': 4,
  'w': 4,
  'x': 8,
  'y': 4,
  'z': 10
}

frequencies = {
  'a': 9,
  'b': 2,
  'c': 2,
  'd': 4,
  'e': 12,
  'f': 2,
  'g': 3,
  'h': 2,
  'i': 9,
  'j': 1,
  'k': 1,
  'l': 4,
  'm': 2,
  'n': 6,
  'o': 8,
  'p': 2,
  'q': 1,
  'r': 6,
  's': 4,
  't': 6,
  'u': 4,
  'v': 2,
  'w': 2,
  'x': 1,
  'y': 2,
  'z': 1
}

healths = {
  'a': 10,
  'b': 12,
  'c': 15,
  'd': 20,
  'e': 15,
  'f': 20,
  'g': 25,
  'h': 20,
  'i': 10,
  'j': 40,
  'k': 20,
  'l': 20,
  'm': 50,
  'n': 0,
  'o': 0,
  'p': 0,
  'q': 0,
  'r': 0,
  's': 0,
  't': 0,
  'u': 0,
  'v': 0,
  'w': 0,
  'x': 0,
  'y': 0,
  'z': 0
}

names = {
  'a': 'ant',
  'b': 'badger',
  'c': 'crawler',
  'd': 'demon',
  'e': 'elf',
  'f': 'fury',
  'g': 'goblin',
  'h': 'hag',
  'i': 'imp',
  'j': 'jabber',
  'k': 'koala',
  'l': 'lemur',
  'm': 'manticore',
  'n': '?',
  'o': '?',
  'p': '?',
  'q': '?',
  'r': '?',
  's': '?',
  't': '?',
  'u': '?',
  'v': '?',
  'w': '?',
  'x': '?',
  'y': '?',
  'z': '?'
}

colors = {
  'a': 'darkred',
  'b': 'brown',
  'c': 'yellow',
  'd': 'red',
  'e': 'green',
  'f': 'darkblue',
  'g': 'darkgreen',
  'h': 'darkgray',
  'i': 'lightblue',
  'j': 'darkbrown',
  'k': 'lightgray',
  'l': 'orange',
  'm': 'darkorange',
  'n': 'white',
  'o': 'white',
  'p': 'white',
  'q': 'white',
  'r': 'white',
  's': 'white',
  't': 'white',
  'u': 'white',
  'v': 'white',
  'w': 'white',
  'x': 'white',
  'y': 'white',
  'z': 'white'
}

abcs = 'abcdefghijklmnopqrstuvwxyz'

with open('foes.js', 'w') as f:
  f.write('var foeKinds = [\n')
  for glyph in abcs:
    f.write("    new FoeKind('{}', '{}', '{}', {}, {}, {}),\n".format(
            glyph, *[d[glyph] for d in (names, colors, healths, values, frequencies)]))
  f.write(']\n')

bingoes = []
with open('/tmp/words') as f:
  for word in f:
    word = word.strip()
    if len(word) == 7:
      bingoes.append((abcs.index(sorted(word)[-1]), word))

def dist(letters):
  total = sum([frequencies[l] for l in letters])
  return { l: frequencies[l]/total for l in letters }

depth_dist = { i+1: dist(abcs[0:i+1])
    for i in range(len(abcs)) }

depth_avg_value = { d: sum([values[l] * depth_dist[d][l]
                            for l in depth_dist[d]])
                    for d in depth_dist }

enemies_per_level = 10

# 100 tiles in Scrabble => ~50 per player, or 7 sets of 7 tiles each game
# If being "good" means getting one bingo per game, and "expert" means three,
# then that translates to "bingo rates" of ~1/7 and 3/7, respectively
#`
# Assuming 10 enemies per level => bingo every 5 levels
# First possible bingo at 5 (acceded), so that one is thrown off (it's not at 2.5)
# But others should be more or less evenly distributed, so smear them out
levels_per_bingo = 7

def experience_after_level():
  total = 0
  for l in range(1, 30):
    old_total = total
    total += (enemies_per_level *
              depth_avg_value[l if l in depth_avg_value else max(depth_avg_value.keys())])
    if (l >= levels_per_bingo):
      total += 50 / levels_per_bingo
    yield l, total

with open('thresholds.js', 'w') as f:
  from math import floor
  f.write('var experienceLevelThresholds = [\n')
  for l, e in [(0, 0)] + sorted(dict(experience_after_level()).items()):
    f.write("  {},\n".format(floor(e)))
  f.write(']\n')
