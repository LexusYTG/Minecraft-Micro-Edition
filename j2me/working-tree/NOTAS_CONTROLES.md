# Controles (Minecraft J2ME)

Teclas por keyCode: NUM0-9, * y #. Joystick/cruceta se mapea a 2/4/6/8/5.

## Dos layouts (se alterna con #)
- Layout A (caminar): 2 adelante, 8 atras, 4/6 girar, 7/9 mirar arriba/abajo, 5 saltar.
- Layout B (camara): 2/8 mirar arriba/abajo, 4/6 girar, 5 avanzar, 7 retroceder, 9 saltar.

## Otras teclas
- 0 = abrir / cerrar inventario
- 1 / 3 = slot anterior / siguiente de la hotbar
- * mantenido = minar / atacar mob
- * toque corto = poner bloque / comer / abrir mesa de crafteo (3x3)

## Inventario
- 2/4/6/8 mueven el cursor, 5 selecciona, * toma/deja de a uno
- Crafteo 2x2 desde 0, 3x3 desde la mesa

## Mundo, memoria y depuracion
- Mundo infinito por chunks de 16x16 (altura 32). En RAM solo una ventana 2x2: el chunk del jugador + el vecino mas cercano por eje (borde = 1 vecino, esquina = 3). Dibujo: 7 bloques (RD).
- Los chunks se guardan en un RecordStore ("mc2dworld"), empaquetados a 4 bits por bloque (4 KB/chunk). Bloques: ids 0..8.
- Un chunk modificado se empaqueta al alejarte; al juntar 16 KB pendientes (SAVE_LIMIT) se escriben, el juego se congela y aparece "Guardando...".
- Cada partida nueva borra el mundo anterior (todavia no hay "Continuar").
- Salto: ~1.3 bloques, alcanza para subir de a un bloque.
- Minecraft2D.DEBUG = true muestra RAM viva del juego, FPS, mobs, chunks (en RAM / pendientes / en disco) y posicion.
