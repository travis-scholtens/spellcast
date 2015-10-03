var FoeKind = function(glyph, name, color, health, value, frequency) {
  this.glyph = glyph;
  this.name = name;
  this.color = color;
  this.health = health;
  this.value = value;
  this.frequency = frequency;
};

var foeDistribution = function(kinds, depth) {
  var dist = {};
  kinds.slice(0, depth).forEach(
      function(k) {
        this[k.glyph] = k.frequency;
      }.bind(dist));
  return dist;
};

var fovFrom = function(level, loc, callback) {
  level.fov.compute(loc.x, loc.y, level.radius,
      function(x, y, _, _) {
        callback({x: x, y: y});
      });
}

var revealFrom = function(level, fromLoc) {
  fovFrom(level, fromLoc,
      function(loc) {
        var tile = level.map[loc.x + ',' + loc.y];
        if (tile != undefined)
          tile.revealed = true;
      }.bind(level));
}

var Game = {
    display: null,
    levels: [],
    engine: null,
    player: null,
    messages: [],
    rack: [],
    rack_size: 6,
    rackTileLocations: {},
    mode: [],

    foeKinds: [
      new FoeKind('a', 'ant', 'darkred', 10, 1, 9),
      new FoeKind('b', 'badger', 'brown', 12, 3, 2),
      new FoeKind('c', 'crawler', 'yellow', 15, 3, 2),
      new FoeKind('d', 'demon', 'red', 20, 2, 4),
      new FoeKind('e', 'elf', 'green', 15, 1, 12),
      new FoeKind('f', 'fury', 'darkblue', 20, 4, 2),
      new FoeKind('g', 'goblin', 'darkgreen', 25, 2, 3),
      new FoeKind('h', 'hag', 'darkgray', 20, 4, 2),
      new FoeKind('i', 'imp', 'lightblue', 10, 1, 9),
      new FoeKind('j', 'jabber', 'darkbrown', 40, 8, 1),
      new FoeKind('k', 'koala', 'lightgray', 20, 5, 1),
      new FoeKind('l', 'lemur', 'orange', 20, 1, 4),
      new FoeKind('m', 'manticore', 'darkorange', 50, 3, 2),
      new FoeKind('n', '?', 'white', 0, 0, 0),
      new FoeKind('o', '?', 'white', 0, 0, 0),
      new FoeKind('p', '?', 'white', 0, 0, 0),
      new FoeKind('q', '?', 'white', 0, 0, 0),
      new FoeKind('r', '?', 'white', 0, 0, 0),
      new FoeKind('s', '?', 'white', 0, 0, 0),
      new FoeKind('t', '?', 'white', 0, 0, 0),
      new FoeKind('u', '?', 'white', 0, 0, 0),
      new FoeKind('v', '?', 'white', 0, 0, 0),
      new FoeKind('w', '?', 'white', 0, 0, 0),
      new FoeKind('x', '?', 'white', 0, 0, 0),
      new FoeKind('y', '?', 'white', 0, 0, 0),
      new FoeKind('z', '?', 'white', 0, 0, 0),
    ],

    init: function() {
        this.display = new ROT.Display({width: 26, height: 26, fontSize: 24, spacing:1});
        var canvas = this.display.getContainer();
        canvas.style.width = '100%';
        canvas.style.height = '100%';
        document.getElementById("main").appendChild(canvas);

        this.values = {};
        for (var i = 0; i < this.foeKinds.length; i++)
          this.values[this.foeKinds[i].glyph] = this.foeKinds[i].value;

        this.glyphKind = {};
        this.foeKinds.forEach(
            function(k) {
              this[k.glyph] = k;
            }.bind(this.glyphKind));
        
        for (var i = 0; i < 26; i++)
          this.levels.push(this._generateMap(i+1));
        this.player = new Player(this.levels[0], this.levels[0].up.x + 2, this.levels[0].up.y);
        revealFrom(this.player.level, {x: this.player._x, y: this.player._y});
        this._drawWholeMap(this.player.level);
        
        this.scheduler = new ROT.Scheduler.Speed();
        this.scheduler.add(this.player, true);
        this.schedule(this.player.level.foes);

        this.engine = new ROT.Engine(this.scheduler);
        this.engine.start();
    },
    
    _generateMap: function(depth) {
        var level = {};
        level.width = 128;
        level.height = 128;
        level.radius = Math.sqrt(level.width * level.width + level.height * level.height);
        level.map = {};
        var digger = new ROT.Map.Digger(level.width, level.height);
        var freeCells = [];
        
        var digCallback = function(x, y, value) {
            if (value) { return; }
            
            var key = x+","+y;
            this.map[key] = new Tile('.');
            freeCells.push(key);
        }
        digger.create(digCallback.bind(level));

        level.fov = new ROT.FOV.PreciseShadowcasting(function(x, y) {
          return (x + ',' + y) in this.map;
        }.bind(level));
        
        var rooms = digger.getRooms();
        var upRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
        var downRoom = upRoom;
        while (downRoom == upRoom)
          downRoom = ROT.RNG.getUniformInt(0, rooms.length-1);

        var upCenter = rooms[upRoom].getCenter(),
            downCenter = rooms[downRoom].getCenter();
        level.up = {x: upCenter[0] - 1, y: upCenter[1]};
        level.down = {x: downCenter[0], y: downCenter[1]};

        var kinds = foeDistribution(this.foeKinds, depth);
        level.foes = [];
        for (var i = 0; i < ROT.RNG.getUniformInt(12, 18); i++) {
          var loc = this.newBeingLocation(freeCells);
          var foe = new Pedro(this.glyphKind[ROT.RNG.getWeightedValue(kinds)], level, loc.x, loc.y);
          level.map[loc.x + ',' + loc.y].contents = foe;
          level.foes.push(foe);
        }

        return level;
    },

    schedule: function(foes) {
      foes.forEach(function(f) { this.add(f, true); }.bind(this.scheduler));
    },

    unschedule: function(foes) {
      foes.forEach(this.scheduler.remove.bind(this.scheduler));
    },

    levelBelow: function(level) {
      return this.levels[Math.min(this.levels.length - 1, this.levels.indexOf(level) + 1)];
    },

    levelAbove: function(level) {
      return this.levels[Math.max(0, this.levels.indexOf(level) - 1)];
    },

    modal: function(size, button) {
      var display = this.display.getOptions();
      var xmax = size.x,
          ymax = size.y;
      var origin = {
          x: (display.width - xmax)/2,
          y: (display.height - ymax)/2 };
      var result = {
          center: {
              x: origin.x + xmax/2,
              y: origin.y + ymax/2 } };
      for (var x = 0; x < xmax; x++)
        for (var y = 0; y < ymax; y++) {
          var glyph = x == 0 ? (y == 0 ? '\u256d' :
                                y == 2 ? '\u251c' :
                                y == ymax - 1 ? '\u2570' :
                                '\u2502') :
                      x == 1 ? (y == 0 ? '\u2500' :
                                y == 1 ? '\u2573' :
                                y == 2 ? '\u2500' :
                                y == ymax - 1 ? '\u2500' :
                                '') :
                      x == 2 ? (y == 0 ? '\u252c' :
                                y == 1 ? '\u2502' :
                                y == 2 ? '\u2518' :
                                y == ymax - 1 ? '\u2500' :
                                '') :
                      x == xmax - 1 ? (y == 0 ? '\u256e':
                                       y == ymax - 1 ? '\u256f' :
                                       '\u2502') :
                      y == 0 || y == ymax  - 1 ? '\u2500' :
                      '';
          this.display.draw(origin.x + x,
                            origin.y + y,
                            glyph);
        }
      result.closeButton = [{
          x: origin.x + 1,
          y: origin.y + 1 }];
      if (button !== undefined) {
        for (x = 0; x < button.length + 2; x++)
          for (y = 0; y < 3; y++) {
            glyph = x == 0 ? (y == 0 ? '\u256d' :
                              y == 2 ? '\u2570' :
                              '\u2502') :
                    x == button.length + 1 ? (y == 0 ? '\u256e' :
                                              y == 2 ? '\u256f' :
                                              '\u2502') :
                    y == 0 || y == 2 ? '\u2500' :
                    '';
            this.display.draw(origin.x + xmax/2 - button.length/2 + x - 1,
                              origin.y + ymax - 4 + y,
                              glyph);
          }
        this.display.drawText(origin.x + xmax/2 - button.length/2,
                              origin.y + ymax - 3,
                              button);
        result.actButton = [];
        for (x = 0; x < button.length; x++)
          result.actButton.push({x: origin.x + xmax/2 - button.length/2 + x,
                                 y: origin.y + ymax - 3});
      }
      return result;
    },

    newBeingLocation: function(freeCells) {
        var index = Math.floor(ROT.RNG.getUniform() * freeCells.length);
        var key = freeCells.splice(index, 1)[0];
        var parts = key.split(",");
        var x = parseInt(parts[0]);
        var y = parseInt(parts[1]);
        return {x: x, y: y};
    },

    _drawWholeMap: function(level) {
      var display = this.display.getOptions();
      var messageCount = this.messages.length;
      for (var i = 0; i < messageCount; i++)
        this.display.drawText(0, i, '> ' + this.messages[i]);
      this.messages.length = 0;
      for (var dx = 0; dx < display.width; dx++)
        for (var dy = messageCount; dy < display.height - 3; dy++) {
          var x = this.player.getX() - display.width/2 + dx,
              y = this.player.getY() - display.height/2 + dy;
          if ((0 <= x && x < level.width) &&
              (0 <= y && y < level.height)) {
            if (x == this.player._x && y == this.player._y)
              this.display.draw(dx, dy, '@', '#ff0');
            else {
              var tile = level.map[x + ',' + y];
              var glyph = '', color = 'white';
              if (tile != undefined && tile.revealed) {
                if (tile.contents != null) {
                  glyph = tile.contents.kind.glyph;
                  color = tile.contents.kind.color;
                } else if (tile.corpse != null) {
                  glyph = tile.corpse;
                  color = 'darkgray';
                } else if (x == level.up.x && y == level.up.y)
                  glyph = '\u261d';
                else if (x == level.down.x && y == level.down.y)
                  glyph = '\u261f';
                else
                  glyph = tile.glyph;
              }
              this.display.draw(dx, dy, glyph, color);
            }
          }
        }
      this.drawRackTiles(level.map[this.player._x + ',' + this.player._y].corpse);
    },

    drawRackTiles: function(corpse) {
      var display = this.display.getOptions();
      var right = 20;
      for (var dx = 0; dx < right; dx++) {
        var glyph = dx == 0 ? '\u2514' :
                    dx == right - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "tan");
      }
      this.rackTileLocations.length = 0;
      for (var i = 0; i < this.rack_size; i++)
        this.clearRackSpace(this.display, 2 + 3 * i, display.height - 2);
      for (var i = 0; i < this.rack.length; i++) {
        this.drawRackTile(
            this.display, 2 + 3 * i, display.height - 2, this.rack[i], 'tan');
        for (var j = 0; j < 2; j++)
          this.rackTileLocations[2 + 3 * i + j] = i;
      }

      var left = 21;
      for (var dx = left; dx < display.width; dx++) {
        var glyph = dx == left ? '\u2514' :
                    dx == display.width - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "gray");
      }

      this.clearRackSpace(this.display, 23, display.height - 2);
      if (corpse != null) {
        this.drawRackTile(
            this.display, 23, display.height - 2, corpse, 'gray');
        for (var j = 0; j < 2; j++)
          this.rackTileLocations[23 + j] = 6;
      }
    },

    clearRackSpace: function(display, x, y) {
      for (var i = 0; i < 3; i++)
        this.display.draw(x - 1 + i, y, '');
    },

    rackTileText: function(glyph, color) {
      return '%b{' + color + '}%c{black}' + glyph;
    },

    drawRackTile: function(display, x, y, glyph, color) {
      var value = this.values[glyph],
          ax,
          digits;
      if (value < 10) {
        ax = x - 0.5;
        digits = [value];
      } else {
        ax = x - 0.75;
        digits = [Math.floor(value/10), value % 10];
      }
      display.drawText(ax, y, this.rackTileText(glyph, color));
      for (var i = 0; i < digits.length; i++)
        display.drawText(ax + 0.75 * i, y,
            this.rackTileText(String.fromCodePoint(0x2080 + digits[i]), color));
    },

    message: function(text) {
      this.messages.push(text);
    }
};

var Tile = function(glyph) {
  this.glyph = glyph;
  this.revealed = false;
  this.contents = null;
  this.corpse = null;
};

var Player = function(level, x, y) {
    this.level = level;
    this._x = x;
    this._y = y;
    this.lastClickEvent = 0;
    this.modalTiles = [];
    this.experience = 0;
    this.woundedness = 0;
}

Player.prototype.getSpeed = function() {
  return this.experience < 10 ? 75 :
         this.experience < 20 ? 85 :
         this.experience < 35 ? 95 :
         this.experience < 55 ? 100 :
         this.experience < 85 ? 105 :
         this.experience < 130 ? 110 :
         this.experience < 200 ? 115 :
         120;
}

Player.prototype.getHealth = function() {
  return (this.experience < 10 ? 20 :
          this.experience < 20 ? 25 :
          this.experience < 35 ? 30 :
          this.experience < 55 ? 35 :
          this.experience < 85 ? 40 :
          this.experience < 130 ? 45 :
          this.experience < 200 ? 50 :
          55) - this.woundedness;
}
    
Player.prototype.getX = function() { return this._x; }
Player.prototype.getY = function() { return this._y; }

Player.prototype.act = function() {
    Game.engine.lock();
    window.addEventListener("keydown", this);
    window.addEventListener("mouseup", this);
    window.addEventListener("touchstart", this);
}

Player.prototype.attack = function(foe) {
  var damage = Math.min(
      Math.max(0, Math.floor(ROT.RNG.getNormal(5, 2))),
      foe.health);
  foe.health -= damage;
  return damage;
}

dropCorpse = function(map, x, y, corpse) {
  var q = [[x,y]];
  var seen = {};
  while (q.length > 0) {
    var u = q.shift();
    var key = u[0] + ',' + u[1];
    if (map[key].corpse == null) {
      map[key].corpse = corpse;
      return;
    }
    seen[key] = true;
    for (var dx = -1; dx <= 1; dx++)
      for (var dy = -1; dy <= 1; dy++)
        if (dx != 0 || dy != 0) {
          var nx = u[0] + dx, ny = u[1] + dy;
          var nkey = nx + ',' + ny;
          if (nkey in map && !(nkey in seen))
            q.push([nx, ny]);
        }
  }
}
    
Player.prototype.keyPress = function(code, level) {
    if (Game.mode.length > 0) return;

    var keyMap = {};
    keyMap[38] = 0;
    keyMap[33] = 1;
    keyMap[39] = 2;
    keyMap[34] = 3;
    keyMap[40] = 4;
    keyMap[35] = 5;
    keyMap[37] = 6;
    keyMap[36] = 7;

    /* one of numpad directions? */
    if (!(code in keyMap)) { return; }

    /* is there a free space? */
    var dir = ROT.DIRS[8][keyMap[code]];
    var newX = this._x + dir[0];
    var newY = this._y + dir[1];
    var newKey = newX + "," + newY;
    if (!(newKey in level.map)) { return; }

    var contents = level.map[newKey].contents;
    if (contents != null) {
      var damage = this.attack(contents);
      var name = Game.glyphKind[contents.kind.glyph].name;
      if (contents.health == 0) {
        level.map[newKey].contents = null;
        dropCorpse(level.map, newX, newY, contents.kind.glyph);
        Game.scheduler.remove(contents);
        Game.message('You kill the ' + name + '!');
      }
      else if (damage > 0)
        Game.message('You hit the ' + name + '! %c{red}(-' + damage + ')');
      else
        Game.message('You miss the ' + name + '.');
    }
    else {
      this._x = newX;
      this._y = newY;
      var key = this._x + ',' + this._y;
      if (level.map[key].corpse != null && Game.rack.length < Game.rack_size) {
        var name = Game.glyphKind[level.map[key].corpse].name;
        Game.rack.push(level.map[key].corpse);
        level.map[key].corpse = null;
        Game.message('You take the ' + name + ' corpse.');
      }
      var stairLoc;
      if (this._x == level.down.x && this._y == level.down.y) {
        this.level = Game.levelBelow(level);
        stairLoc = this.level.up;
      }
      else if (this._x == level.up.x && this._y == level.up.y) {
        this.level = Game.levelAbove(level);
        stairLoc = this.level.down;
      }
      if (this.level != level) {
        Game.unschedule(level.foes);
        Game.schedule(this.level.foes);
        this._x = stairLoc.x;
        this._y = stairLoc.y;
      }
      revealFrom(this.level, {x: this._x, y: this._y});
    }
    window.removeEventListener("keydown", this);
    window.removeEventListener("mouseup", this);
    window.removeEventListener("touchstart", this);
    Game.engine.unlock();
}

Player.prototype.clickRackTile = function(index, map) {
  var result,
      button,
      width,
      height;
  if (index < Game.rack.length) {
    var remaining = Game.rack.splice(index);
    this.modalTiles.push({glyph: remaining.shift(), fromRack: true, color: 'tan'});
    Game.rack = Game.rack.concat(remaining);
  } else if (index == 6) {
    var key = Game.player._x + ',' + Game.player._y
    var glyph = map[key].corpse;
    if (glyph != null) {
      this.modalTiles.push({glyph: glyph, fromRack: false, color: 'gray'});
      map[key].corpse = null;
    }
  }
  if (this.modalTiles.length > 0) {
    button = this.modalTiles.length > 1 ? 'Cast' :
             this.modalTiles[0].fromRack ? 'Drop' :
             undefined;
    width = button !== undefined ? Math.min(26, Math.max(12, 6 + 3 * this.modalTiles.length)) : 8;
    height = button !== undefined ? 8 : 4;
    result = Game.modal({x: width, y: height}, button);
    for (var i = 0; i < this.modalTiles.length; i++) {
      var padding = this.modalTiles.length == 1 ? 1 : 0;
      Game.drawRackTile(
          Game.display,
          result.center.x - width/2 + 3 * i + 4 + padding,
          result.center.y - height/4,
          this.modalTiles[i].glyph, this.modalTiles[i].color);
    }
    Game.drawRackTiles();
    if (Game.mode.length > 0)
      Game.mode.pop();
    Game.mode.push(function(player, index, tiles, closeButton, actButton) {
        return function(loc) {
          var clicked = false;
          for (var i = 0; i < closeButton.length; i++)
            if (loc.x == closeButton[i].x && loc.y == closeButton[i].y) {
              for (var j = 0; j < tiles.length; j++) {
                if (tiles[j].fromRack)
                  Game.rack.push(tiles[j].glyph);
                else
                  map[Game.player._x + ',' + Game.player._y].corpse = tiles[j].glyph;
              }
              tiles.length = 0;
              clicked = true;
              break;
            }
          if (!clicked && actButton !== undefined)
            for (var i = 0; i < actButton.length; i++)
              if (loc.x == actButton[i].x && loc.y == actButton[i].y) {
                if (tiles.length == 1) {
                  var name = Game.glyphKind[tiles[0].glyph].name;
                  dropCorpse(map, Game.player._x, Game.player._y, tiles[0].glyph);
                  Game.message('You drop the ' + name + ' corpse.');
                } else {
                  var spell = '', score = 0;
                  for (var j = 0; j < tiles.length; j++) {
                    spell += tiles[j].glyph;
                    score += Game.values[tiles[j].glyph];
                  }
                  if (spell.length == 7)
                    score += 50;
                  if (spell in words) {
                    player.experience += score;
                    Game.message('You cast ' + spell.toUpperCase() + '. %c{green}(+' + score + ')');
                  }
                  else
                    Game.message('Nothing happens.');
                }
                tiles.length = 0;
                clicked = true;
                break;
              }
          if (clicked)
            Game.mode.pop();
        }; }(this, index, this.modalTiles, result.closeButton, result.actButton));
  }
}

Player.prototype.click = function(loc) {
  if (loc.y == 24) {
    var rackTileIndex = Game.rackTileLocations[loc.x];
    if (rackTileIndex !== undefined)
      this.clickRackTile(rackTileIndex, this.level.map);
  } else if (Game.mode.length > 0)
    Game.mode[Game.mode.length-1](loc);
}

ROT.Display.prototype.eventToClientPosition = function(e) {
  if (e.touches) {
    var x = e.touches[0].clientX;
    var y = e.touches[0].clientY;
  } else {
    var x = e.clientX;
    var y = e.clientY;
  }

  var rect = this._context.canvas.getBoundingClientRect();
  x -= rect.left;
  y -= rect.top;

  x *= this._context.canvas.width / this._context.canvas.clientWidth;
  y *= this._context.canvas.height / this._context.canvas.clientHeight;

  if (x < 0 || y < 0 || x >= this._context.canvas.width || y >= this._context.canvas.height)
    return [-1, -1];

  return this._backend.eventToPosition(x, y);
}

Player.prototype.handleEvent = function(e) {
  if (e.type == 'keydown')
    this.keyPress(e.keyCode, this.level);
  else {
    var now = new Date().getTime();
    if (now - this.lastClickEvent < 1000)
      return;
    this.lastClickEvent = now;
    var coords = Game.display.eventToClientPosition(e);
    if (coords[0] >= 0 && coords[1] >= 0)
      this.click({x: coords[0], y: coords[1]});
  }
  if (Game.mode.length == 0)
    Game._drawWholeMap(this.level);
}
    
var Pedro = function(kind, level, x, y) {
    this.level = level;
    this._x = x;
    this._y = y;
    this.kind = kind;
    this.health = kind.health;
    this.lastSawPlayer = null;
}

Pedro.prototype.getSpeed = function() { return 100; }

var pathTo = function(map, fromLoc, toLoc) {
  var astar = new ROT.Path.AStar(toLoc.x, toLoc.y,
      function(x, y) {
        return (x+","+y in this);
      }.bind(map),
      {topology:4});

  var path = [];
  astar.compute(fromLoc.x, fromLoc.y,
      function(x, y) {
        this.push([x, y]);
      }.bind(path));

  path.shift();
  return path.length > 0 ?
         { x: path[0][0], y: path[0][1] } :
         null;
}

var locInView = function(level, fromLoc, targetLoc) {
  var canSeeTarget = false;
  fovFrom(level, fromLoc,
      function(loc) {
        if (loc.x == this.x && loc.y == this.y)
          canSeeTarget = true;
      }.bind(targetLoc));
  return canSeeTarget;
}

Pedro.prototype.attack = function(player) {
  var damage = Math.min(
      Math.max(0, Math.floor(ROT.RNG.getNormal(3, 1))),
      player.getHealth());
  player.woundedness += damage;
  return damage;
}

Pedro.prototype.act = function() {
  var loc = { x: this._x, y: this._y },
      playerLoc = { x: Game.player._x, y: Game.player._y },
      inView = locInView(this.level, loc, playerLoc),
      nextLoc = inView || this.lastSawPlayer !== null ?
          pathTo(this.level.map, loc, playerLoc) : null;

  if (inView)
    this.lastSawPlayer = 0;
  else if (this.lastSawPlayer !== null && this.lastSawPlayer < 3)
    this.lastSawPlayer++;
  else
    this.lastSawPlayer = null;

  if (nextLoc == null)
    return;

  if (nextLoc.x == playerLoc.x && nextLoc.y == playerLoc.y) {
    var damage = this.attack(Game.player);
    if (Game.player.getHealth() == 0)
      Game.message('The ' + this.kind.name + ' kills you!');
    else if (damage > 0)
      Game.message('The ' + this.kind.name + ' hits you! %c{red}(-' + damage + ')');
    else
      Game.message('The ' + this.kind.name + ' misses you.');
  } else {
    if (this.level.map[nextLoc.x + ',' + nextLoc.y].contents != null)
      return;
    this.level.map[this._x + ',' + this._y].contents = null;
    this._x = nextLoc.x;
    this._y = nextLoc.y;
    this.level.map[this._x + ',' + this._y].contents = this;
  }
}
