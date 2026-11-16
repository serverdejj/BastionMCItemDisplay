# BastionMCItemDisplay v0.1.0-beta

Proyecto fuente para **Purpur 1.21.11 / Java 21**. Subir a GitHub y compilar con GitHub Actions (workflow incluido).

## Comandos

- `/rareza item comun|pococomun|raro|epico|legendario|mitico|reset` — solo OP o `bastionitemdisplay.admin`, con objeto en mano.
- `/itemdisplay reload` — recarga YAML.

## Comportamiento

- El nombre flotante usa el componente de nombre personalizado del objeto o el nombre traducible nativo. No cambia el nombre al asignar rareza.
- La rareza personalizada se guarda en el PersistentDataContainer del ItemMeta. `reset` la elimina.
- Se intenta consultar la rareza nativa a través de la API. Si no está expuesta, se utiliza un fallback limitado para algunos objetos conocidos y `comun` para los demás. **No es todavía una lectura universal del componente minecraft:rarity**.
- Los objetos cercanos tienen una entidad TextDisplay asociada. Las entidades son transitorias y se retiran cuando el objeto desaparece o se aleja.
- El brillo es el contorno nativo (`setGlowing`); **el color del contorno no se cambia** en esta beta para no interferir con equipos scoreboard de otros plugins. Las partículas sí usan los colores de rareza.
- La información al apuntar aparece en la actionbar. Los atributos de combate muestran los modificadores explícitos del ItemMeta, cuando existen; no se calculan todos los atributos base de Minecraft.

## Advertencias

**BETA NO PROBADA**: se requiere compilación y pruebas en un servidor de pruebas antes de usar en producción. Los TextDisplay por objeto pueden afectar al rendimiento en zonas con miles de objetos; ajustar distancias y períodos en config.yml. Si se usa otro plugin de etiquetas de objetos (como ItemStacker), desactivar su función de nombres para evitar duplicados.
