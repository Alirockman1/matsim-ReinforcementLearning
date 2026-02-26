import xml.etree.ElementTree as ET
from xml.dom import minidom

def update_siouxfalls_network(input_path, output_path):
    print(f"Reading {input_path}...")
    
    # Parse the XML
    tree = ET.parse(input_path)
    root = tree.getroot()
    links_element = root.find('links')
    
    if links_element is None:
        print("Error: Could not find <links> section.")
        return

    # list() creates a copy so we can add new links without affecting the loop
    original_links = list(links_element.findall('link'))
    print(f"Processing {len(original_links)} existing links...")

    for link in original_links:
        # --- TASK 1: Update existing link modes ---
        # Change modes="car" to modes="car,bike"
        current_modes = link.get('modes', '')
        if 'car' in current_modes and 'bike' not in current_modes:
            new_modes = current_modes + ',bike'
            link.set('modes', new_modes)

        # --- TASK 2: Create parallel sidewalk link ---
        link_id = link.get('id')
        from_node = link.get('from')
        to_node = link.get('to')
        length = link.get('length')
        
        sidewalk = ET.Element('link')
        sidewalk.set('id', f"{link_id}_sidewalk")
        sidewalk.set('from', from_node)
        sidewalk.set('to', to_node)
        sidewalk.set('length', length)
        
        # Attributes for sidewalks (standard walking speed 1.3 m/s)
        sidewalk.set('freespeed', '1.3') 
        sidewalk.set('capacity', '9999') # High capacity to prevent walking traffic jams
        sidewalk.set('permlanes', '1.0')
        sidewalk.set('modes', 'network_walk,bike')
        
        links_element.append(sidewalk)

    print("Formatting XML for readability...")
    
    # Convert tree to string for pretty-printing
    raw_str = ET.tostring(root, encoding='utf-8')
    reparsed = minidom.parseString(raw_str)
    
    # Fix for minidom adding extra blank lines to existing pretty XML
    pretty_xml_str = reparsed.toprettyxml(indent="    ")
    pretty_xml_str = "\n".join([line for line in pretty_xml_str.split('\n') if line.strip()])

    # Write to file
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(pretty_xml_str)
    
    print(f"Success! Final network saved to: {output_path}")

# Run the script
if __name__ == "__main__":
    # Ensure your 'network.xml' is in the same directory
    update_siouxfalls_network('E:\\MyFolder\\Upskill\\MatSIM\\matsim-Basic\\scenarios\\sioux-falls\\modified\\input\\network.xml', 'E:\\MyFolder\\Upskill\\MatSIM\\matsim-Basic\\scenarios\\sioux-falls\\modified\\input\\network_with_sidewalks.xml')
