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

abcs = 'abcdefghijklmnopqrstuvwxyz'

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
