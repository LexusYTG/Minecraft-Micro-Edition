# Minicraft Micro Edition

**Minecraft Micro Edition** es un clon del juego Minicraft pensado para consumir muy pocos recursos y ejecutarse en hardware poco potente, buscando democratizar la experiencia Minicraft.

Es un solo juego para todas las plataformas posibles. **No es** Minecraft Java Edition ni Minecraft Bedrock Edition; es Minicraft Micro Edition.

---

## 🎮 Plataformas y Versiones

El juego cuenta con múltiples versiones compiladas a partir de distintos lenguajes y entornos, las cuales se mantienen en paridad de contenido. Esto permite que la experiencia de juego sea idéntica sin importar el dispositivo en el que se ejecute.

### Releases
Cada release es una sola versión del juego, con el mismo contenido en todas las plataformas. Las distintas compilaciones siempre coinciden en número de versión.

👉 **[Descargar la última versión en la sección de Releases](../../releases)**
*(Esta sección tiene una rama/compilación para cada plataforma soportada actualmente).*

---

## 🕹️ Controles

El esquema de control se adapta a las capacidades de cada dispositivo (teclados físicos, teclados numéricos, pantallas táctiles o gamepads).

La lógica principal de movimiento, acción y navegación por menús se mantiene consistente entre todas las versiones, permitiendo alternar entre distintos modos de cámara, gestionar el inventario y realizar acciones básicas como romper bloques, colocar objetos, atacar o interactuar con el entorno.

> *Los detalles específicos sobre el mapeo de teclas o botones para cada versión se encuentran documentados internamente en cada carpeta de desarrollo.*

---

## 🛠️ Compilar

El proceso de compilación requiere las herramientas y el entorno de desarrollo adecuados para cada lenguaje y plataforma.

Cada versión cuenta con su propio script automatizado de construcción. Al ejecutarlo, se procesa el código fuente correspondiente y se genera el ejecutable final, el cual queda alojado en la carpeta de compilación designada para cada entorno.

### Estructura del repositorio

```text
.
├── README.md
├── [carpetas de las distintas versiones]
│   ├── build.sh (scripts de compilación, no universal)
│   ├── working-tree/
│   └── src (código fuente y recursos)
└── ...
```

---

📜 Legales

Todo el código fuente corre bajo la licencia GPL v3.
